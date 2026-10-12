package com.translator.telegram.model

data class MessageItem(
    val id: String,
    val originalText: String,
    var translatedText: String? = null,
    val screenTop: Int = 0,
    val screenBottom: Int = 0
)
