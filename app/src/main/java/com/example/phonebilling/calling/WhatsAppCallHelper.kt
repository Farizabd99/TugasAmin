package com.example.phonebilling.calling

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Menangani alur panggilan WhatsApp terbatas (Bypass Chat Screen).
 *
 * Alur kerja:
 * 1. Masukkan kontak sementara ke database Kontak Android.
 * 2. Kirim sinyal sinkronisasi paksa ke akun WhatsApp.
 * 3. Poll database kontak untuk mencari Data ID VoIP WhatsApp.
 * 4. Luncurkan Intent langsung ke WhatsApp VoIP Activity.
 * 5. Bersihkan kontak sementara setelah selesai.
 *
 * Catatan: Metode ini menggunakan jalur tidak resmi (undocumented) dari WhatsApp.
 * WhatsApp harus terinstal dan versinya dikunci (auto-update dimatikan).
 */
class WhatsAppCallHelper @Inject constructor() {

    companion object {
        private const val TAG = "WartelCall"

        /** Timeout polling maksimum dalam milidetik */
        private const val SYNC_TIMEOUT_MS = 10_000L

        /** Interval polling dalam milidetik */
        private const val POLL_INTERVAL_MS = 500L

        /** Jumlah iterasi polling = SYNC_TIMEOUT_MS / POLL_INTERVAL_MS */
        private const val MAX_POLL_ITERATIONS = (SYNC_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()

        /** MIME type untuk panggilan suara WhatsApp */
        const val MIME_VOIP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"

        /** MIME type untuk panggilan video WhatsApp */
        const val MIME_VIDEO_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

        /** Package name WhatsApp Consumer */
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        /** Account type WhatsApp di sistem Android */
        private const val WHATSAPP_ACCOUNT_TYPE = "com.whatsapp"

        /** Prefix nama kontak sementara */
        private const val TEMP_CONTACT_PREFIX = "WartelTemp_"

        /** Base URI data kontak Android */
        private val CONTACTS_DATA_URI: Uri = Data.CONTENT_URI
    }

    // ─────────────────────────────────────────────────────────────
    // 1. FORMAT NOMOR TELEPON
    // ─────────────────────────────────────────────────────────────

    /**
     * Normalisasi nomor telepon Indonesia ke format internasional tanpa '+'.
     * Contoh: "0822..." → "62822...", "62822..." → "62822...", "822..." → "62822..."
     */
    fun formatToInternational(number: String): String {
        val clean = number.filter { it.isDigit() }
        return when {
            clean.startsWith("0") -> "62" + clean.substring(1)
            clean.startsWith("62") -> clean
            else -> "62$clean"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. PENANGANAN KONTAK SEMENTARA
    // ─────────────────────────────────────────────────────────────

    /**
     * Masukkan kontak sementara ke database Kontak Android.
     *
     * @param context Konteks Android
     * @param phoneNumber Nomor telepon format internasional (misal "628xxxxxxxxxx")
     * @return URI kontak yang baru dibuat, atau null jika gagal
     */
    fun insertTemporaryContact(context: Context, phoneNumber: String): Uri? {
        val resolver = context.contentResolver
        val contactName = "$TEMP_CONTACT_PREFIX${System.currentTimeMillis()}"

        Log.d(TAG, "Inserting temporary contact: name=$contactName, phone=$phoneNumber")

        val ops = ArrayList<ContentProviderOperation>()

        // Operasi 0: Buat RawContact baru (tanpa akun agar tidak di-sync ke cloud)
        ops.add(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build()
        )

        // Operasi 1: Tambah nama kontak
        ops.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, contactName)
                .build()
        )

        // Operasi 2: Tambah nomor telepon
        ops.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(CommonDataKinds.Phone.NUMBER, "+$phoneNumber")
                .withValue(CommonDataKinds.Phone.TYPE, CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )

        return try {
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val uri = results.firstOrNull()?.uri
            Log.d(TAG, "Temporary contact inserted successfully: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert temporary contact", e)
            null
        }
    }

    /**
     * Hapus kontak sementara dari database Kontak Android.
     *
     * @param context Konteks Android
     * @param contactUri URI kontak yang akan dihapus
     */
    fun deleteTemporaryContact(context: Context, contactUri: Uri) {
        try {
            val deleted = context.contentResolver.delete(contactUri, null, null)
            Log.d(TAG, "Temporary contact deleted: $contactUri (rows=$deleted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete temporary contact: $contactUri", e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. SINKRONISASI KONTAK WHATSAPP
    // ─────────────────────────────────────────────────────────────

    /**
     * Kirim sinyal sinkronisasi paksa ke akun WhatsApp melalui ContentResolver.
     * WhatsApp akan mencocokkan kontak baru dengan database pengguna mereka.
     *
     * @param context Konteks Android
     * @return true jika berhasil memicu sinkronisasi, false jika akun WhatsApp tidak ditemukan
     */
    fun requestSync(context: Context): Boolean {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.accounts
        var syncTriggered = false

        for (account in accounts) {
            if (account.type == WHATSAPP_ACCOUNT_TYPE) {
                Log.d(TAG, "Found WhatsApp account: ${account.name}. Triggering sync...")
                val extras = Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
                ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
                syncTriggered = true
            }
        }

        if (!syncTriggered) {
            Log.w(TAG, "No WhatsApp account found on device! Sync cannot be triggered.")
        }

        return syncTriggered
    }

    // ─────────────────────────────────────────────────────────────
    // 4. PENCARIAN DATA ID VoIP WHATSAPP
    // ─────────────────────────────────────────────────────────────

    /**
     * Cari Data ID VoIP WhatsApp yang sesuai dengan nomor telepon yang diberikan.
     *
     * @param context Konteks Android
     * @param phoneNumber Nomor telepon format internasional (misal "628xxxxxxxxxx")
     * @param isVideo true untuk video call, false untuk voice call
     * @return Data._ID jika ditemukan, atau null jika tidak ditemukan
     */
    fun findVoipDataId(context: Context, phoneNumber: String, isVideo: Boolean): Long? {
        val resolver = context.contentResolver
        val mimeType = if (isVideo) MIME_VIDEO_CALL else MIME_VOIP_CALL

        val projection = arrayOf(
            Data._ID,
            Data.DATA1,
            Data.DISPLAY_NAME
        )
        val selection = "${Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(mimeType)

        Log.d(TAG, "Querying contacts DB for mimeType=$mimeType, targetNumber=$phoneNumber")

        val cursor = resolver.query(
            CONTACTS_DATA_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        if (cursor == null) {
            Log.w(TAG, "Query returned null cursor")
            return null
        }

        try {
            val idCol = cursor.getColumnIndex(Data._ID)
            val data1Col = cursor.getColumnIndex(Data.DATA1)
            val nameCol = cursor.getColumnIndex(Data.DISPLAY_NAME)
            val cleanTarget = phoneNumber.filter { it.isDigit() }

            Log.d(TAG, "Found ${cursor.count} rows for mimeType=$mimeType")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val data1 = cursor.getString(data1Col) ?: ""
                val name = cursor.getString(nameCol) ?: ""
                val cleanData1 = data1.filter { it.isDigit() }

                Log.d(TAG, "  Row ID=$id, Name='$name', DATA1='$data1', cleanDATA1='$cleanData1'")

                // Cocokkan nomor: salah satu harus mengandung yang lain
                if (cleanData1.contains(cleanTarget) || cleanTarget.contains(cleanData1)) {
                    Log.d(TAG, "  ✓ MATCH FOUND! Returning dataId=$id")
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying VoIP data ID", e)
        } finally {
            cursor.close()
        }

        Log.d(TAG, "  ✗ No match found for number=$phoneNumber")
        return null
    }

    // ─────────────────────────────────────────────────────────────
    // 5. PELUNCURAN PANGGILAN
    // ─────────────────────────────────────────────────────────────

    /**
     * Luncurkan panggilan WhatsApp secara langsung (bypass chat screen).
     *
     * Alur lengkap:
     * 1. Buat kontak sementara
     * 2. Trigger sinkronisasi WhatsApp
     * 3. Poll untuk Data ID VoIP
     * 4. Luncurkan intent langsung ke WhatsApp VoIP
     * 5. Bersihkan kontak sementara
     *
     * Jika gagal, fallback ke deep link wa.me (yang akan membuka chat screen).
     *
     * @param context Konteks Android
     * @param phoneNumber Nomor telepon format internasional (misal "628xxxxxxxxxx")
     * @param isVideo true untuk video call, false untuk voice call
     * @param onLoadingStateChanged Callback untuk memperbarui state loading di UI
     * @param coroutineScope CoroutineScope untuk menjalankan proses async
     */
    fun launchCall(
        context: Context,
        phoneNumber: String,
        isVideo: Boolean,
        onLoadingStateChanged: (Boolean) -> Unit,
        coroutineScope: CoroutineScope
    ) {
        val callType = if (isVideo) "VIDEO" else "VOICE"
        Log.d(TAG, "═══════════════════════════════════════════════")
        Log.d(TAG, "Starting $callType call to $phoneNumber")
        Log.d(TAG, "═══════════════════════════════════════════════")

        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { onLoadingStateChanged(true) }

            var tempContactUri: Uri? = null
            var callIntentStarted = false

            try {
                // ─── Langkah 1: Buat Kontak Sementara ───
                Log.d(TAG, "[Step 1/5] Inserting temporary contact...")
                tempContactUri = insertTemporaryContact(context, phoneNumber)

                if (tempContactUri == null) {
                    Log.e(TAG, "[Step 1/5] FAILED: Could not insert temporary contact")
                    return@launch
                }
                Log.d(TAG, "[Step 1/5] OK: Contact created at $tempContactUri")

                // ─── Langkah 2: Trigger Sinkronisasi WhatsApp ───
                Log.d(TAG, "[Step 2/5] Requesting WhatsApp sync...")
                val syncTriggered = requestSync(context)
                if (!syncTriggered) {
                    Log.w(TAG, "[Step 2/5] WARNING: No WhatsApp account found, proceeding anyway...")
                } else {
                    Log.d(TAG, "[Step 2/5] OK: Sync triggered")
                }

                // ─── Langkah 3: Poll untuk Data ID VoIP ───
                Log.d(TAG, "[Step 3/5] Polling for VoIP data ID (max ${SYNC_TIMEOUT_MS / 1000}s)...")
                for (i in 1..MAX_POLL_ITERATIONS) {
                    delay(POLL_INTERVAL_MS)
                    val dataId = findVoipDataId(context, phoneNumber, isVideo)

                    if (dataId != null) {
                        Log.d(TAG, "[Step 3/5] OK: Found dataId=$dataId after ${i * POLL_INTERVAL_MS}ms")

                        // ─── Langkah 4: Luncurkan Intent VoIP ───
                        val mimeType = if (isVideo) MIME_VIDEO_CALL else MIME_VOIP_CALL
                        Log.d(TAG, "[Step 4/5] Launching direct $callType intent...")

                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                Uri.parse("content://com.android.contacts/data/$dataId"),
                                mimeType
                            )
                            `package` = WHATSAPP_PACKAGE
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        withContext(Dispatchers.Main) {
                            context.startActivity(intent)
                        }
                        callIntentStarted = true
                        Log.d(TAG, "[Step 4/5] OK: Direct call intent launched!")
                        break
                    }

                    if (i % 4 == 0) {
                        Log.d(TAG, "[Step 3/5] Still polling... (${i * POLL_INTERVAL_MS / 1000}s elapsed)")
                    }
                }

                if (!callIntentStarted) {
                    Log.w(TAG, "[Step 3/5] TIMEOUT: Could not find VoIP data ID after ${SYNC_TIMEOUT_MS / 1000}s")
                }
            } catch (e: android.content.ActivityNotFoundException) {
                Log.e(TAG, "WhatsApp is not installed or VoIP activity not found", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing required permissions (READ_CONTACTS / WRITE_CONTACTS)", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during call launch", e)
            } finally {
                // ─── Langkah 5: Bersihkan Kontak Sementara ───
                if (tempContactUri != null) {
                    Log.d(TAG, "[Step 5/5] Cleaning up temporary contact...")
                    deleteTemporaryContact(context, tempContactUri)
                    Log.d(TAG, "[Step 5/5] OK: Cleanup complete")
                }

                withContext(Dispatchers.Main) {
                    onLoadingStateChanged(false)

                    // Fallback ke deep link wa.me jika panggilan langsung gagal
                    if (!callIntentStarted) {
                        Log.w(TAG, "Direct call failed. Falling back to wa.me deep link...")
                        launchFallbackDeepLink(context, phoneNumber, isVideo)
                    }
                }

                Log.d(TAG, "═══════════════════════════════════════════════")
                Log.d(TAG, "$callType call flow completed. Success=$callIntentStarted")
                Log.d(TAG, "═══════════════════════════════════════════════")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 6. FALLBACK (DEEP LINK WA.ME)
    // ─────────────────────────────────────────────────────────────

    /**
     * Fallback: Buka WhatsApp chat screen melalui deep link wa.me.
     * Ini adalah jalur cadangan jika panggilan langsung gagal.
     *
     * ⚠ PERINGATAN: Metode ini AKAN membuka ruang obrolan (chat screen).
     *
     * @param context Konteks Android
     * @param phoneNumber Nomor telepon format internasional
     * @param isVideo true untuk menampilkan panduan video call
     */
    private fun launchFallbackDeepLink(context: Context, phoneNumber: String, isVideo: Boolean) {
        val guideMessage = if (isVideo) {
            "Membuka WhatsApp... Silakan ketuk ikon Video Call (kamera) di pojok kanan atas."
        } else {
            "Membuka WhatsApp... Silakan ketuk ikon Telepon di pojok kanan atas."
        }
        Toast.makeText(context, guideMessage, Toast.LENGTH_LONG).show()

        runCatching {
            val url = "https://wa.me/$phoneNumber"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                `package` = WHATSAPP_PACKAGE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Fallback deep link launched: $url")
        }.onFailure { e ->
            Log.e(TAG, "Fallback deep link also failed!", e)
            Toast.makeText(
                context,
                "Gagal membuka WhatsApp. Pastikan WhatsApp terinstall.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
