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
                Log.d(TAG, "Found WhatsApp account: ${account.name}. Enabling and triggering sync...")
                try {
                    ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
                    ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable sync automatically", e)
                }
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
        Log.d(TAG, "Starting accessibility-based $callType call to $phoneNumber")
        Log.d(TAG, "═══════════════════════════════════════════════")

        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                // Tampilkan overlay loading layar penuh
                OverlayService.start(context)
                onLoadingStateChanged(true)
            }

            try {
                // Set parameter di Accessibility Service agar siap memencet tombol
                WartelAccessibilityService.targetCallType = callType
                
                // Daftarkan callback untuk menghilangkan overlay setelah tombol berhasil ditekan
                WartelAccessibilityService.onCallTriggered = {
                    coroutineScope.launch(Dispatchers.Main) {
                        // Tunggu 3 detik agar panggilan WhatsApp benar-benar termulai secara visual,
                        // baru kemudian hilangkan overlay untuk menampilkan UI panggilan WhatsApp.
                        delay(3000)
                        OverlayService.stop(context)
                        onLoadingStateChanged(false)
                    }
                }

                // Tambahkan timeout pengaman jika service tidak berhasil mengklik dalam 8 detik
                launch {
                    delay(8000)
                    if (WartelAccessibilityService.targetCallType != null) {
                        Log.w(TAG, "Accessibility timeout reached! Stopping overlay.")
                        WartelAccessibilityService.targetCallType = null
                        withContext(Dispatchers.Main) {
                            OverlayService.stop(context)
                            onLoadingStateChanged(false)
                            Toast.makeText(context, "Gagal menyambungkan otomatis. Silakan coba lagi.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Buka chat room WhatsApp menggunakan deep link (ini akan menutupi aplikasi kita,
                // tapi chat room WhatsApp akan tertutupi oleh overlay dari OverlayService)
                val url = "https://wa.me/$phoneNumber"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    `package` = WHATSAPP_PACKAGE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting accessibility call flow", e)
                withContext(Dispatchers.Main) {
                    OverlayService.stop(context)
                    onLoadingStateChanged(false)
                }
            }
        }
    }
}
