package com.clashlite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashlite.ui.AppTheme
import com.clashlite.ui.ClashNav

class MainActivity : ComponentActivity() {

    private val vm: ClashViewModel by viewModels { ClashViewModel.Factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            AppTheme(config = theme) {
                ClashNav(vm)
            }
        }
    }
}
