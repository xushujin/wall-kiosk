package com.wallkiosk.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wallkiosk.console.ui.WallKioskApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 主题在 WallKioskApp 内部按设置套上，这里不预先包一层
        setContent { WallKioskApp() }
    }
}
