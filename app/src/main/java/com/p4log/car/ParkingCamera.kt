package com.p4log.car

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/**
 * 주차 사진: 전면 카메라로 프리뷰 화면 없이 1장 촬영.
 *
 * 2026-08-30 재작성 — 실차에서 "시간 초과(8초)"로 계속 실패하던 문제.
 * 원인: JPEG 리더 하나만 붙여 놓고 곧바로 STILL_CAPTURE를 던졌다.
 * 카메라 HAL은 반복 요청(프리뷰 스트림)으로 노출·화이트밸런스가 수렴하기 전에는
 * 정지 프레임을 안 내주는 경우가 많다 → 응답 없이 그대로 멈춘다.
 * 이제 작은 YUV 리더로 **워밍업 스트림을 먼저 돌린 뒤** 정지 촬영을 요청한다.
 *
 * 실패 이유는 문자열로 돌려준다 (실차엔 logcat이 없어 ServiceLog로만 원인을 볼 수 있다).
 */
object ParkingCamera {

    private const val TAG = "P4Log.Cam"
    private const val TIMEOUT_MS = 14000L      // 워밍업 포함이라 넉넉히
    private const val WARMUP_FRAMES = 8        // 이만큼 받으면 노출이 잡힌 것으로 본다
    private const val WARMUP_MAX_MS = 3000L    // 프레임이 안 와도 이 시간이면 그냥 찍는다

    private fun errName(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "다른 앱이 사용 중"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "카메라 동시 사용 한도"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "정책상 카메라 차단(백그라운드 제한)"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "카메라 장치 오류"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "카메라 서비스 오류"
        else -> "알 수 없는 오류 " + code
    }

    private fun facingName(cm: CameraManager, id: String): String = try {
        when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "실내"
            CameraMetadata.LENS_FACING_BACK -> "후방"
            CameraMetadata.LENS_FACING_EXTERNAL -> "외장"
            else -> "불명"
        }
    } catch (e: Throwable) { "?" }

    /**
     * 차량이 앱에 노출하는 카메라 전부: (id, 방향).
     * 순정 블랙박스 앱은 **실외향 카메라**로 사진을 찍는다(폴스타4 매뉴얼).
     * 우리가 실패하던 건 실내(전면) 카메라만 고집했기 때문일 수 있다 (2026-09-02).
     */
    fun listCameras(context: Context): List<Pair<String, String>> = try {
        val cm = context.getSystemService(CameraManager::class.java)
        cm.cameraIdList.map { it to facingName(cm, it) }
    } catch (e: Throwable) { emptyList() }

    /**
     * 성공: (파일, null) / 실패: (null, 이유). 콜백은 워커 스레드에서 호출됨.
     * @param cameraId 지정하면 그 카메라만 연다. null이면 실내 → 첫 번째 순으로 고른다
     */
    fun captureAsync(context: Context, cameraId: String? = null, cb: (File?, String?) -> Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cb(null, "카메라 권한 없음"); return
        }

        val thread = HandlerThread("parking-cam").apply { start() }
        val handler = Handler(thread.looper)
        var done = false
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var jpegReader: ImageReader? = null
        var warmReader: ImageReader? = null
        var warmCount = 0
        var stillRequested = false
        var camTagRef = ""

        fun finish(file: File?, err: String?) {
            if (done) return
            done = true
            try { session?.close() } catch (e: Throwable) {}
            try { camera?.close() } catch (e: Throwable) {}
            try { jpegReader?.close() } catch (e: Throwable) {}
            try { warmReader?.close() } catch (e: Throwable) {}
            thread.quitSafely()
            if (err != null) Log.w(TAG, "capture failed: " + err)
            cb(file, err)
        }

        handler.postDelayed({
            finish(null, camTagRef + "시간 초과 (" + (TIMEOUT_MS / 1000) + "초, 워밍업 " + warmCount + "프레임)")
        }, TIMEOUT_MS)

        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val camId = cameraId ?: (cm.cameraIdList.firstOrNull { id ->
                try {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                        CameraMetadata.LENS_FACING_FRONT
                } catch (e: Throwable) { false }
            } ?: cm.cameraIdList.firstOrNull())
            if (camId == null) { finish(null, "사용 가능한 카메라 없음"); return }
            val camTag = "cam" + camId + "(" + facingName(cm, camId) + ") "
            camTagRef = camTag

            val map = cm.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)
                ?.minByOrNull { Math.abs(it.width * it.height - 1280 * 720) }
            if (jpegSize == null) { finish(null, "카메라 " + camId + " 가 JPEG 출력을 지원하지 않음"); return }
            // 워밍업용 저해상도 스트림 (프레임은 받는 즉시 버린다)
            val warmSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { Math.abs(it.width * it.height - 640 * 480) } ?: jpegSize

            val jr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            jpegReader = jr
            jr.setOnImageAvailableListener({ rd ->
                try {
                    val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    img.close()
                    val file = File(context.filesDir, "parking.jpg")
                    file.writeBytes(bytes)
                    Log.i(TAG, "captured " + bytes.size + " bytes")
                    finish(file, null)
                } catch (e: Throwable) {
                    finish(null, "저장 실패: " + e.javaClass.simpleName)
                }
            }, handler)

            val wr = ImageReader.newInstance(
                warmSize.width, warmSize.height, ImageFormat.YUV_420_888, 3)
            warmReader = wr

            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    if (done) { try { cam.close() } catch (e: Throwable) {}; return }
                    try {
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(listOf(wr.surface, jr.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    if (done) return

                                    fun shoot() {
                                        if (stillRequested || done) return
                                        stillRequested = true
                                        try {
                                            try { s.stopRepeating() } catch (e: Throwable) {}
                                            val req = cam.createCaptureRequest(
                                                CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            req.addTarget(jr.surface)
                                            req.set(CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON)
                                            req.set(CaptureRequest.JPEG_ORIENTATION, 0)
                                            s.capture(req.build(),
                                                object : CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureFailed(
                                                        sess: CameraCaptureSession,
                                                        request: CaptureRequest,
                                                        failure: CaptureFailure
                                                    ) {
                                                        finish(null,
                                                            "촬영 거부됨 (reason=" + failure.reason + ")")
                                                    }
                                                }, handler)
                                        } catch (e: Throwable) {
                                            finish(null, "촬영 요청 실패: " + e.javaClass.simpleName)
                                        }
                                    }

                                    // 워밍업: 프레임이 충분히 오면 바로, 안 오면 시간으로 촬영
                                    wr.setOnImageAvailableListener({ rd ->
                                        try { rd.acquireLatestImage()?.close() } catch (e: Throwable) {}
                                        warmCount++
                                        if (warmCount >= WARMUP_FRAMES) shoot()
                                    }, handler)
                                    handler.postDelayed({ shoot() }, WARMUP_MAX_MS)

                                    try {
                                        val pre = cam.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW)
                                        pre.addTarget(wr.surface)
                                        s.setRepeatingRequest(pre.build(), null, handler)
                                    } catch (e: Throwable) {
                                        // 프리뷰가 안 되면 바로 정지 촬영이라도 시도
                                        Log.w(TAG, "preview failed, shooting directly", e)
                                        shoot()
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    finish(null, camTag + "촬영 세션 구성 실패")
                                }
                            }, handler)
                    } catch (e: Throwable) {
                        finish(null, "세션 생성 실패: " + e.javaClass.simpleName)
                    }
                }
                override fun onDisconnected(cam: CameraDevice) {
                    finish(null, camTag + "카메라 연결 끊김 (차량이 회수)")
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    finish(null, camTag + errName(error))
                }
            }, handler)
        } catch (e: Throwable) {
            finish(null, e.javaClass.simpleName + ": " + (e.message ?: "-"))
        }
    }
}
