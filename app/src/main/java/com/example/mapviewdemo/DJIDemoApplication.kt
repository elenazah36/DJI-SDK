package com.example.mapviewdemo

import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.gimbal.Gimbal
import dji.sdk.products.Aircraft
import dji.sdk.products.HandHeld
import dji.sdk.sdkmanager.DJISDKManager

object DJIDemoApplication {

    fun getProductInstance(): BaseProduct? {
        return DJISDKManager.getInstance().product
    }

    fun getCameraInstance(): Camera? {
        if (getProductInstance() == null) return null

        if (getProductInstance() is Aircraft) {
            return (getProductInstance() as Aircraft).camera
        } else if (getProductInstance() is HandHeld) {
            return (getProductInstance() as HandHeld).camera
        } else
            return null
    }

    fun getFlightController(): FlightController? {
        // returns flight controller, this is what you will use
        // to tell the drone how to move
        val product = getProductInstance()?: return null
        if (product.isConnected) {
            if (product is Aircraft) {
                return product.flightController
            }
        }
        return null
    }
    fun getGimbal(): Gimbal? {
        val product = getProductInstance() ?: return null
        if (product.isConnected) {
            if (product is Aircraft) {
                return product.gimbal
            }
        }
        return null
    }
}