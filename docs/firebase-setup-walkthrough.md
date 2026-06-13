# Panduan Integrasi Firebase (Fase 2)

Dokumen ini berisi panduan langkah-demi-langkah untuk mengintegrasikan Firebase ke dalam aplikasi **Restricted Phone Billing** (Wartel Android), menggantikan server HTTP lokal (`qa-server.js` / Retrofit) dengan **Firebase Firestore** dan **Firebase Authentication** untuk sinkronisasi multi-device secara real-time.

---

## Langkah 1: Pengaturan di Firebase Console

1. Buka [Firebase Console](https://console.firebase.google.com/).
2. Klik **Add Project** (Tambah Proyek), masukkan nama proyek (misal: `Wartel-Android`), lalu klik **Continue**.
3. Aktifkan atau nonaktifkan Google Analytics sesuai kebutuhan (untuk demo/QA bisa dinonaktifkan agar lebih cepat), lalu klik **Create Project**.
4. Setelah proyek selesai dibuat, masuk ke halaman dasbor proyek.

---

## Langkah 2: Registrasi Aplikasi Android

1. Di dasbor Firebase, klik ikon **Android** untuk menambahkan aplikasi.
2. Masukkan **Android package name**: `com.example.phonebilling` (harus sesuai dengan `applicationId` di `app/build.gradle.kts`).
3. (Opsional) Masukkan nama samaran aplikasi (misal: `Wartel Client & Operator`).
4. (Opsional) Masukkan sertifikat tanda tangan SHA-1 (dibutuhkan jika ingin menggunakan Google Sign-In atau fitur Firebase Auth tertentu). Anda bisa mendapatkannya dengan menjalankan perintah berikut di terminal proyek:
   ```bash
   ./gradlew signingReport
   ```
5. Klik **Register App**.
6. Unduh file `google-services.json`.
7. Pindahkan/salin file `google-services.json` tersebut ke dalam direktori modul **`app/`** di proyek Anda:
   ```text
   d:\Wartel\app\google-services.json
   ```
8. Kembali ke Firebase Console, klik **Next**, lalu selesaikan wisaya (wizard).

---

## Langkah 3: Konfigurasi Gradle (Kotlin DSL & Version Catalog)

Kita perlu memperbarui konfigurasi Gradle proyek agar mendukung plugin Google Services dan SDK Firebase.

### 1. Perubahan pada `gradle/libs.versions.toml`

Tambahkan plugin Google Services dan dependensi Firebase ke file version catalog:

```toml
[versions]
# ... versi yang sudah ada ...
googleServices = "4.4.2"
firebaseBom = "33.7.0" # Atau versi stabil terbaru

[libraries]
# ... library yang sudah ada ...
# Firebase Bill of Materials (BoM) & Services
firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebaseBom" }
firebase-firestore = { module = "com.google.firebase:firebase-firestore" }
firebase-auth = { module = "com.google.firebase:firebase-auth" }

[plugins]
# ... plugin yang sudah ada ...
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

### 2. Perubahan pada `build.gradle.kts` (Root/Project)

Daftarkan plugin Google Services di blok `plugins`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false // <- TAMBAHKAN INI
}
```

### 3. Perubahan pada `app/build.gradle.kts` (Module: app)

Terapkan plugin Google Services dan tambahkan dependensi Firebase:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services) // <- TAMBAHKAN INI
}

// ... konfigurasi android ...

dependencies {
    // ... dependensi yang sudah ada ...

    // Integrasi Firebase BoM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
}
```

---

## Langkah 4: Desain Skema Database Firestore (Fase 2)

Untuk menggantikan API HTTP lokal, kita akan memetakan model data ke dalam koleksi Cloud Firestore. Firestore mendukung sinkronisasi real-time secara bawaan, sehingga Client Device tidak perlu lagi melakukan polling berkala.

### 1. Koleksi `devices`
Menyimpan status dan informasi setiap perangkat client yang terdaftar.

- **Path Dokumen:** `devices/{deviceId}`
- **Struktur Dokumen:**
  ```json
  {
    "deviceId": "string",
    "displayName": "string",
    "model": "string",
    "mode": "CLIENT",
    "status": "WAITING | ACTIVE | EXPIRED",
    "lastSeenAt": "timestamp (server time)"
  }
  ```

### 2. Koleksi `sessions`
Menyimpan sesi aktif dan riwayat penagihan yang dibuat oleh Operator.

- **Path Dokumen:** `sessions/{sessionId}`
- **Struktur Dokumen:**
  ```json
  {
    "sessionId": "string",
    "deviceId": "string",
    "tariffId": "string",
    "operatorId": "string",
    "status": "ACTIVE | STOPPED | EXPIRED",
    "startedAt": "timestamp",
    "endsAt": "timestamp",
    "stoppedAt": "timestamp | null",
    "extendedMinutes": "number",
    "priceCents": "number"
  }
  ```

---

## Langkah 5: Transisi Arsitektur Kode dari HTTP ke Firestore Real-Time

Saat ini, aplikasi menggunakan Retrofit untuk polling status di Client Mode:
- Client memanggil `GET /api/devices/{deviceId}/status` setiap beberapa detik.
- Menggunakan Firebase Firestore, kita dapat mengganti polling ini dengan **Snapshot Listener** yang mendengarkan perubahan status dokumen perangkat secara instan:

```kotlin
// Contoh implementasi listener real-time di FirestoreBillingRepository
class FirestoreBillingRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    fun observeDeviceStatus(deviceId: String): Flow<DeviceStatus?> = callbackFlow {
        val docRef = firestore.collection("devices").document(deviceId)
        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val status = snapshot.toObject(DeviceStatusDto::class.java)?.toDomain()
                trySend(status)
            } else {
                trySend(null)
            }
        }
        awaitClose { listener.remove() }
    }
}
```

### Keuntungan Integrasi Firebase:
1. **Zero Polling Delay:** Kiosk akan mengunci atau membuka secara instan begitu Operator menekan tombol di dasbor.
2. **Efisiensi Baterai & Kuota:** Koneksi WebSocket Firestore jauh lebih hemat energi dibandingkan polling HTTP berulang.
3. **Penyimpanan Offline Otomatis:** SDK Firestore memiliki fitur *offline persistence* bawaan, sehingga aplikasi tetap berjalan lancar saat jaringan tidak stabil dan akan otomatis melakukan sinkronisasi saat tersambung kembali.
