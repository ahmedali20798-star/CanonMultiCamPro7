package com.canonmulticam.pro.transport

import com.canonmulticam.pro.domain.CameraHandshakeInfo

object CameraDriverFactory {

    suspend fun connectAndLockProtocol(
        ipAddress: String
    ): Result<Pair<ICameraDriver, CameraHandshakeInfo>> {
        val ccapi = CcapiCameraDriver(ipAddress)
        val ccapiHandshake = ccapi.handshake()
        if (ccapiHandshake.isSuccess) {
            return Result.success(ccapi to ccapiHandshake.getOrThrow())
        }
        ccapi.close()

        val ptpIp = PtpIpCameraDriver(ipAddress)
        val ptpHandshake = ptpIp.handshake()
        if (ptpHandshake.isSuccess) {
            return Result.success(ptpIp to ptpHandshake.getOrThrow())
        }
        ptpIp.close()

        return Result.failure(
            ptpHandshake.exceptionOrNull()
                ?: IllegalStateException("Camera unreachable on CCAPI & PTP/IP")
        )
    }
}
