package com.raytek.pocketdrop

import android.app.Application
import android.content.SharedPreferences

class LanSendApplication : Application(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var pairingPreferences: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        pairingPreferences = getSharedPreferences("pocketdrop", MODE_PRIVATE)
        PairedPcStore.synchronizeLegacyPairing(this)
        pairingPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (PairedPcStore.isLegacyPairingKey(key)) {
            PairedPcStore.synchronizeLegacyPairing(this)
        }
    }
}
