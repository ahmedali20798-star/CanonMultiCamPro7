package com.canonmulticam.pro

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.canonmulticam.pro.engine.MultiCamViewModel
import com.canonmulticam.pro.transport.CameraDiscoveryService
import com.canonmulticam.pro.transport.NetworkBinder
import com.canonmulticam.pro.ui.MultiCamScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MultiCamViewModel by viewModels()
    private lateinit var discoveryService: CameraDiscoveryService

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startCameraDiscovery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NetworkBinder.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        discoveryService = CameraDiscoveryService(this)
        requestNetworkPermissionsAndStart()

        setContent {
            MultiCamScreen(viewModel = viewModel)
        }
    }

    private fun requestNetworkPermissionsAndStart() {
        val requiredPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()

        permissionLauncher.launch(requiredPermissions)
    }

    private fun startCameraDiscovery() {
        discoveryService.startDiscovery(
            connectedCountProvider = { viewModel.slotsState.value.count { it.isConnected } }
        ) { camera ->
            viewModel.onCameraDiscovered(camera)
        }
    }

    override fun onDestroy() {
        discoveryService.destroy()
        super.onDestroy()
    }
}
