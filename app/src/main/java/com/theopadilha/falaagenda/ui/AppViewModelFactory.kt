package com.theopadilha.falaagenda.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.ui.home.HomeViewModel

class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(container) as T
        }
        throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
