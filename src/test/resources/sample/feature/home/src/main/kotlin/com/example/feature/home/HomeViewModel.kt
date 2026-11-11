package com.example.feature.home

import androidx.lifecycle.ViewModel
import com.example.data.ItemRepository
import com.example.data.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val items: ItemRepository,
    private val tags: TagRepository,
    private val lazyItems: Provider<ItemRepository>,
) : ViewModel()
