package com.example.app

import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import com.example.data.ItemRepository
import com.example.feature.home.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var repo: ItemRepository

    private val vm: HomeViewModel by viewModels()
}

class ListFragment
class DetailFragment
class UnusedFragment
