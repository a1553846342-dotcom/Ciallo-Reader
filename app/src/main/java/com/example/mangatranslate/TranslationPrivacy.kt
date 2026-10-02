package com.example.mangatranslate

import android.content.Context

internal object TranslationPrivacy {
    fun allowed(context:Context):Boolean = !com.example.data.PrivacyManager(context).isEnabled() &&
        !com.example.data.PreferencesManager(context).incognitoBrowsingEnabled
}
