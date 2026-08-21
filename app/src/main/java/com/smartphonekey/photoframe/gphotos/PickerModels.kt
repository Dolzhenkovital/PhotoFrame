package com.smartphonekey.photoframe.gphotos

/** A Picker API session (photospicker.googleapis.com/v1/sessions). */
data class PickerSession(
    val id: String,
    val pickerUri: String,
    val pollIntervalMs: Long,
    val timeoutMs: Long,
    val mediaItemsSet: Boolean,
)

/** One media item the user picked. baseUrl expires in ~60 min — download now. */
data class PickedItem(
    val id: String,
    val filename: String,
    val mimeType: String,
    val baseUrl: String,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
)

data class PickedPage(
    val items: List<PickedItem>,
    val nextPageToken: String?,
)
