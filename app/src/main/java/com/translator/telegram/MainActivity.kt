package com.translator.telegram

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.translator.telegram.databinding.ActivityMainBinding
import com.translator.telegram.service.OverlayService
import com.translator.telegram.service.TelegramAccessibilityService
import com.translator.telegram.translation.GoogleTranslateApi
import com.translator.telegram.translation.TranslationRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        loadPrefixSettings()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateServiceButtonState()
    }

    private fun loadPrefixSettings() {
        val currentPrefix = TranslationRepository.getPrefix(this)
        binding.etCustomPrefix.setText(currentPrefix)
        binding.tvPreviewText.text = "${currentPrefix}Привет, как твои дела сегодня?"
    }

    private fun setupListeners() {
        // Save prefix button
        binding.btnSavePrefix.setOnClickListener {
            val newPrefix = binding.etCustomPrefix.text.toString()
            TranslationRepository.setPrefix(this, newPrefix)
            binding.tvPreviewText.text = "${newPrefix}Привет, как твои дела сегодня?"
            Toast.makeText(this, "Префикс сохранён: $newPrefix", Toast.LENGTH_SHORT).show()
        }

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
                    Toast.makeText(this, "Разрешение поверх окон уже включено", Toast.LENGTH_SHORT).show()
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
                TelegramAccessibilityService.instance?.hideFloatingBubble()
            } else {
                val startIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(this, startIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                TelegramAccessibilityService.instance?.showFloatingBubble()
                Toast.makeText(this, "Стеклянная кнопка запущена! Перейдите в Telegram", Toast.LENGTH_SHORT).show()
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
                    val prefix = TranslationRepository.getPrefix(this@MainActivity)
                    binding.tvTestResult.text = "${prefix}${translated}"
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
            binding.btnToggleFloatingBubble.setBackgroundResource(R.drawable.bg_liquid_glass)
        } else {
            binding.btnToggleFloatingBubble.text = "Запустить стеклянную кнопку"
            binding.btnToggleFloatingBubble.setBackgroundResource(R.drawable.bg_glass_pill_button)
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
