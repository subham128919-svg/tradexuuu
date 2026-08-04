package com.tradingapp.util
sealed class Resource<out T> { object Loading : Resource<Nothing>(); data class Success<T>(val data: T) : Resource<T>(); data class Error(val message: String) : Resource<Nothing>() }
inline fun <T> Resource<T>.onSuccess(block: (T) -> Unit): Resource<T> { if (this is Resource.Success) block(data); return this }
inline fun <T> Resource<T>.onError(block: (String) -> Unit): Resource<T> { if (this is Resource.Error) block(message); return this }
