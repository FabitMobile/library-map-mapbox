package ru.fabit.mapmapbox

interface PermissionProvider {

    fun isLocationPermissionGranted(): Boolean
}