package com.umbra.app.domain.nip25

/**
 * NIP-25: a `-` reaction is a dislike. Every other content (`+`, empty, any emoji or custom
 * `:shortcode:`) is a positive reaction, so a dislike must never be counted as one.
 */
fun isDislikeReactionContent(content: String): Boolean = content.trim() == "-"
