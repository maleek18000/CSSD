package com.arabicsource

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class ArabicSourceSettingsDialog : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragmentContainer = FrameLayout(requireContext())
        fragmentContainer.id = View.generateViewId()
        fragmentContainer.layoutParams = ViewGroup.LayoutParams(-1, -1)
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment())
            .commit()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(-1, -1)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.white)
    }

    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val category = PreferenceCategory(ctx)
            category.title = "المصدر العربي - تسجيل الدخول"
            screen.addPreference(category)

            val usernamePref = EditTextPreference(ctx).apply {
                key = "arabicsource_username"
                title = "اسم المستخدم"
                summary = "اسم المستخدم في موقع arabicsource.net"
                dialogTitle = "Username"
            }
            category.addPreference(usernamePref)

            val passwordPref = EditTextPreference(ctx).apply {
                key = "arabicsource_password"
                title = "كلمة المرور"
                summary = "كلمة المرور في موقع arabicsource.net"
                dialogTitle = "Password"
            }
            category.addPreference(passwordPref)

            val statusPref = Preference(ctx).apply {
                title = "حالة تسجيل الدخول"
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val cookies = prefs.getString("arabicsource_cookies", "")
                val username = prefs.getString("arabicsource_username", "")
                summary = if (username.isNullOrBlank()) "لم يتم تسجيل الدخول"
                else if (cookies.isNullOrBlank()) "تم حفظ البيانات ولكن لم يتم الاتصال بعد"
                else "مسجل الدخول ($username)"
                isEnabled = false
            }
            category.addPreference(statusPref)

            val resetPref = Preference(ctx).apply {
                title = "إعادة تعيين الكوكيز"
                summary = "حذف الكوكيز المحفوظة لإجبار تسجيل الدخول مجدداً"
                setOnPreferenceClickListener {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                    prefs.edit().remove("arabicsource_cookies").apply()
                    statusPref.summary = "تم حذف الكوكيز..."
                    true
                }
            }
            category.addPreference(resetPref)

            val closePref = Preference(ctx).apply {
                title = "إغلاق"
                setOnPreferenceClickListener {
                    (parentFragment as? DialogFragment)?.dismiss()
                    true
                }
            }
            screen.addPreference(closePref)
        }
    }
}