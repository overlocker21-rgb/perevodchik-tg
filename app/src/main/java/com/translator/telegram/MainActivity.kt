package com.translator.telegram

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.translator.telegram.databinding.ActivityMainBinding
import com.translator.telegram.service.OverlayService
import com.translator.telegram.service.TelegramAccessibilityService
import com.translator.telegram.translation.GoogleTranslateApi
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateServiceButtonState()
    }

    private fun setupListeners() {
        // Overlay permission button
        binding.btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Разрешение поверх окон уже предоставлено", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Accessibility service button
        binding.btnGrantAccessibility.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Найдите «TG Translator Overlay» в списке и включите",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Служба спец. возможностей уже включена", Toast.LENGTH_SHORT).show()
            }
        }

        // Toggle Floating Bubble button
        binding.btnToggleFloatingBubble.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдайте разрешение «Поверх других окон»", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (OverlayService.isRunning) {
                val stopIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP
                }
                startService(stopIntent)
            } else {
                val startIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                }
                ContextCompat.startForegroundService(this, startIntent)
                Toast.makeText(this, "Плавающий значок запущен! Перейдите в Telegram", Toast.LENGTH_SHORT).show()
            }

            binding.root.postDelayed({ updateServiceButtonState() }, 300)
        }

        // Test Translation button
        binding.btnTestTranslate.setOnClickListener {
            val textToTranslate = binding.etTestInput.text.toString().trim()
            if (textToTranslate.isEmpty()) {
                Toast.makeText(this, "Введите текст для проверки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.tvTestResult.text = "Переводим через Google Translate..."

            lifecycleScope.launch {
                val result = GoogleTranslateApi.translate(textToTranslate)
                result.onSuccess { translated ->
                    binding.tvTestResult.text = "Результат:\n$translated"
                }.onFailure { error ->
                    binding.tvTestResult.text = "Ошибка: ${error.localizedMessage ?: "Сбой соединения"}"
                }
            }
        }
    }

    private fun updatePermissionStatuses() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (hasOverlay) {
            binding.btnGrantOverlay.text = "Включено"
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.btnGrantOverlay.text = "Включить"
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }

        if (hasAccessibility) {
            binding.btnGrantAccessibility.text = "Включено"
            binding.btnGrantAccessibility.isEnabled = false
            binding.btnGrantAccessibility.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.btnGrantAccessibility.text = "Включить"
            binding.btnGrantAccessibility.isEnabled = true
            binding.btnGrantAccessibility.setTextColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun updateServiceButtonState() {
        if (OverlayService.isRunning) {
            binding.btnToggleFloatingBubble.text = "Остановить переводчик"
            binding.btnToggleFloatingBubble.setBackgroundColor(ContextCompat.getColor(this, R.color.error))
        } else {
            binding.btnToggleFloatingBubble.text = "Запустить плавающую кнопку"
            binding.btnToggleFloatingBubble.setBackgroundResource(R.drawable.bg_button)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (TelegramAccessibilityService.isRunning) return true

        val expectedService = ComponentName(this, TelegramAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
