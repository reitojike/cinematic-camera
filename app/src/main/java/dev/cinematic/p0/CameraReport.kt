package dev.cinematic.p0

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest

data class CameraReport(
    val id: String,
    val text: String,
    val isoRange: IntRange?,
    val exposureRange: LongRange?,
    val manualBlockReason: String?,
    val autoFocusAvailable: Boolean = false,
    val overrides: OverrideCapabilities = OverrideCapabilities(),
)

object CameraReports {
    fun read(context: Context, log: (String) -> Unit): List<CameraReport> {
        val manager = context.getSystemService(CameraManager::class.java)
        return manager.cameraIdList.mapNotNull { id ->
            try {
                val c = manager.getCameraCharacteristics(id)
                val caps = c.get(C.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
                val iso = c.get(C.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }
                val ns = c.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }
                val keys = c.availableCaptureRequestKeys.orEmpty()
                val afModes = c.get(C.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val wbModes = c.get(C.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
                val antiBandingModes = c.get(C.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toSet().orEmpty()
                val minimumFocus = c.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val calibration = when (c.get(C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                    C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
                    C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
                    C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
                    else -> "UNKNOWN"
                }
                val overrideCapabilities = OverrideCapabilities(
                    if (CaptureRequest.CONTROL_AWB_MODE in keys) wbModes.filter { it in 1..8 }.toSet() else emptySet(),
                    if (CaptureRequest.CONTROL_AE_ANTIBANDING_MODE in keys) antiBandingModes.filter { it in 0..3 }.toSet() else emptySet(),
                    minimumFocus?.takeIf { C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps &&
                        it.isFinite() && it > 0f && CaptureRequest.CONTROL_AF_MODE_OFF in afModes &&
                        CaptureRequest.CONTROL_AF_MODE in keys && CaptureRequest.LENS_FOCUS_DISTANCE in keys },
                    calibration,
                )
                val reason = Exposure.manualBlockReason(
                    C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                    c.get(C.CONTROL_AE_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true,
                    listOf(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.SENSOR_SENSITIVITY,
                        CaptureRequest.SENSOR_EXPOSURE_TIME).all { it in keys }, iso, ns)
                val facing = when (c.get(C.LENS_FACING)) {
                    C.LENS_FACING_BACK -> "BACK"; C.LENS_FACING_FRONT -> "FRONT"; else -> "EXTERNAL/UNKNOWN"
                }
                val level = when (c.get(C.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    C.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    C.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    C.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    C.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    C.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }
                val text = """
                    Camera2 id=$id facing=$facing hardware=$level
                    physicalIds=${c.physicalCameraIds.sorted()}
                    focalLengthsMm=${c.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.contentToString()}
                    RAW=${C.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps} MANUAL_SENSOR=${C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps}
                    ISO=$iso exposureNs=$ns maxFrameDurationNs=${c.get(C.SENSOR_INFO_MAX_FRAME_DURATION)}
                    minimumFocusDistanceLensUnits=${c.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}
                    focusCalibration=$calibration (UNCALIBRATED/UNKNOWN does not guarantee distance in meters)
                    overrideEligibility=$overrideCapabilities
                    apertures=${c.get(C.LENS_INFO_AVAILABLE_APERTURES)?.contentToString()} (display only)
                    RAW_SENSOR sizes=${c.get(C.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)?.joinToString() ?: "none/unknown"}
                    manualEligibility=${reason ?: "eligible; actual results NOT verified"}
                    AF modes=${afModes.contentToString()} maxRegionsAF=${c.get(C.CONTROL_MAX_REGIONS_AF)} maxRegionsAE=${c.get(C.CONTROL_MAX_REGIONS_AE)}
                    AWB modes=${c.get(C.CONTROL_AWB_AVAILABLE_MODES)?.contentToString()} antiBandingModes=${c.get(C.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.contentToString()}
                    AE compensation range=${c.get(C.CONTROL_AE_COMPENSATION_RANGE)} step=${c.get(C.CONTROL_AE_COMPENSATION_STEP)}
                """.trimIndent()
                log(text)
                CameraReport(id, text, iso, ns, reason,
                    afModes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF && it != CaptureRequest.CONTROL_AF_MODE_EDOF }, overrideCapabilities)
            } catch (e: Exception) {
                log("ERROR Camera2 report id=$id ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }
}
