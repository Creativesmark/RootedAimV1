package com.example.rootedaimv1;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * AimAssistManager.java
 *
 * Single-file manager for:
 *  - loading a YOLOv8 tflite model from assets
 *  - running inference on Bitmap frames
 *  - simple post-processing (conf threshold + NMS)
 *  - estimating head-center from bbox
 *  - selecting best target by scoring (confidence, distance to screen center, bbox size)
 *  - smoothing aim using EMA + small PID-ish step output
 *
 * // USAGE:
 * // AimAssistManager aim = new AimAssistManager(getAssets(), "yolov8.tflite", true);
 * // PointF delta = aim.getAimDelta(frameBitmap, currentAimPoint);
 * //
 * Note: This code assumes the TFLite model outputs a tensor in the common YOLO format:
 *   [num_boxes, (x, y, w, h, conf, class_scores...)]
 * If your model differs, inspect the interpreter tensor shapes and adapt parseModelOutput(...)
 *
 * IMPORTANT: Use for your own game development / testing only.
 */

public class AimAssistManager {

    private static final String TAG = "AimAssistManager";

    // -------------------------
    // Configuration parameters
    // -------------------------
    private final float CONF_THRESHOLD = 0.30f;    // min detection confidence
    private final float NMS_IOU = 0.45f;           // NMS IoU threshold
    private final float HEAD_Y_FACTOR = 0.22f;     // head center relative vertical within bbox (tweak)
    private final float SMOOTH_ALPHA = 0.22f;      // EMA smoothing factor (0..1) lower = smoother/slower
    private final float CENTER_WEIGHT = 0.45f;     // scoring: weight for center proximity
    private final float CONF_WEIGHT = 0.40f;       // scoring: weight for detection confidence
    private final float SIZE_WEIGHT = 0.15f;       // scoring: weight for bbox size (closer = higher)
    private final int MAX_OUTPUT_BOXES = 25200;    // fallback; adjust if your model smaller

    // -------------------------
    // TFLite objects
    // -------------------------
    private Interpreter interpreter = null;
    private GpuDelegate gpuDelegate = null;
    private NnApiDelegate nnApiDelegate = null;
    private int inputWidth = 320;
    private int inputHeight = 320;
    private int inputChannels = 3;

    // runtime buffers
    private ByteBuffer inputBuffer = null;

    // smoothing state
    private float smoothX = -1f, smoothY = -1f;

    // simple debug toggle
    private boolean debug = false;

    // constructor
    public AimAssistManager(AssetManager assets, String modelFilename, boolean useGpu) throws Exception {
        loadModel(assets, modelFilename, useGpu);
    }

    // -------------------------
    // Model loading
    // -------------------------
    private void loadModel(AssetManager assets, String modelFilename, boolean useGpu) throws Exception {
        MappedByteBuffer model = loadModelFile(assets, modelFilename);
        Interpreter.Options options = new Interpreter.Options();
        try {
            if (useGpu) {
                gpuDelegate = new GpuDelegate();
                options.addDelegate(gpuDelegate);
            } else {
                // Optionally try NNAPI (uncomment to try)
                // nnApiDelegate = new NnApiDelegate();
                // options.addDelegate(nnApiDelegate);
            }
            options.setNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
            interpreter = new Interpreter(model, options);

            // Query input tensor to set input width/height dynamically
            Tensor inputTensor = interpreter.getInputTensor(0);
            int[] shape = inputTensor.shape(); // e.g. [1, H, W, C]
            if (shape.length == 4) {
                inputHeight = shape[1];
                inputWidth = shape[2];
                inputChannels = shape[3];
            }
            if (debug) Log.i(TAG, "Loaded model. Input size: " + inputWidth + "x" + inputHeight + "x" + inputChannels);
            // allocate input buffer
            inputBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * inputChannels);
            inputBuffer.order(ByteOrder.nativeOrder());
        } catch (Exception e) {
            // clean up
            if (gpuDelegate != null) { gpuDelegate.close(); gpuDelegate = null; }
            if (nnApiDelegate != null) { nnApiDelegate.close(); nnApiDelegate = null; }
            throw e;
        }
    }

    private MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename) throws IOException {
        AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    // -------------------------
    // Public API: getAimDelta
    // - frameBitmap: current screenshot/frame (ARGB_8888)
    // - currentAim: current aim position on screen (PointF)
    // returns: delta vector (dx, dy) in screen pixels to apply towards head center
    // -------------------------
    public PointF getAimDelta(Bitmap frameBitmap, PointF currentAim) {
        long t0 = SystemClock.elapsedRealtime();

        // --- 1) run detection
        List<Detection> detections = detect(frameBitmap);

        // --- 2) extract head centers from boxes
        int frameW = frameBitmap.getWidth();
        int frameH = frameBitmap.getHeight();
        List<Target> targets = new ArrayList<>();
        for (Detection d : detections) {
            PointF head = bboxToHeadCenter(d, frameW, frameH);
            Target t = new Target();
            t.center = head;
            t.confidence = d.conf;
            t.area = d.w * d.h; // normalized area
            targets.add(t);
        }

        if (targets.size() == 0) {
            // no targets -> small decay of smoothing to avoid jumps
            if (smoothX < 0) return new PointF(0f, 0f);
            smoothX = smoothX + (currentAim.x - smoothX) * 0.06f;
            smoothY = smoothY + (currentAim.y - smoothY) * 0.06f;
            return new PointF(0f, 0f);
        }

        // --- 3) score & choose best target
        PointF screenCenter = new PointF(frameW / 2f, frameH / 2f);
        for (Target t : targets) {
            float dist = dist(t.center, screenCenter);
            float normDist = dist / (float)Math.hypot(frameW / 2.0, frameH / 2.0); // 0..1
            float normArea = Math.min(1f, t.area); // area should be normalized already
            t.score = CONF_WEIGHT * t.confidence + CENTER_WEIGHT * (1f - normDist) + SIZE_WEIGHT * normArea;
        }
        Collections.sort(targets, (a, b) -> Float.compare(b.score, a.score));
        Target chosen = targets.get(0);

        // --- 4) smoothing (EMA)
        if (smoothX < 0) { // first frame init
            smoothX = chosen.center.x;
            smoothY = chosen.center.y;
        } else {
            smoothX = SMOOTH_ALPHA * chosen.center.x + (1f - SMOOTH_ALPHA) * smoothX;
            smoothY = SMOOTH_ALPHA * chosen.center.y + (1f - SMOOTH_ALPHA) * smoothY;
        }

        // --- 5) compute delta to move aim (small step)
        float dx = smoothX - currentAim.x;
        float dy = smoothY - currentAim.y;

        // apply a small cap so we don't jump huge amounts; tuned for more natural motion
        float maxStep = Math.max(frameW, frameH) * 0.2f; // cap at 20% of screen for safety
        float stepLen = (float)Math.hypot(dx, dy);
        if (stepLen > maxStep) {
            float scale = maxStep / stepLen;
            dx *= scale; dy *= scale;
        }

        long t1 = SystemClock.elapsedRealtime();
        if (debug) Log.d(TAG, "Aim delta computed in " + (t1-t0) + "ms; chosen score=" + chosen.score + " conf=" + chosen.confidence);

        return new PointF(dx, dy);
    }

    // -------------------------
    // Detection wrapper: runs TFLite inference and returns list of Detection (normalized coords)
    // -------------------------
    private List<Detection> detect(Bitmap bitmap) {
        // 1) prepare bitmap -> inputBuffer (float32 normalized to 0..1)
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        convertBitmapToByteBuffer(resized, inputBuffer);

        // 2) run interpreter
        // Attempt to read output tensor shape dynamically
        Tensor outTensor = interpreter.getOutputTensor(0);
        int[] outShape = outTensor.shape(); // e.g. [1, N, D]
        int numBoxes = 0;
        int boxVecLen = 6; // default [x,y,w,h,conf,class] or [x,y,w,h,conf] + classes
        boolean hasClasses = false;
        if (outShape.length == 3) {
            numBoxes = outShape[1];
            boxVecLen = outShape[2];
            hasClasses = boxVecLen > 6;
        } else {
            // fallback
            numBoxes = MAX_OUTPUT_BOXES;
        }

        // prepare output buffer: float[numBoxes][boxVecLen]
        float[][][] output = new float[1][numBoxes][boxVecLen];
        Object[] inputs = new Object[] { inputBuffer };
        java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, output);

        // Run inference
        interpreter.runForMultipleInputsOutputs(inputs, outputs);

        // Parse outputs to Detection list
        List<Detection> raw = new ArrayList<>();
        for (int i = 0; i < numBoxes; i++) {
            float[] vec = output[0][i];
            if (vec.length < 5) continue;
            // some models use [x,y,w,h,obj_conf,cls_conf...] or [x_center,y_center,w,h,obj_conf,classes...]
            float x = vec[0];
            float y = vec[1];
            float w = vec[2];
            float h = vec[3];
            float objConf = vec[4];
            float bestClassConf = 0f;
            int bestClass = -1;
            if (hasClasses) {
                for (int c = 5; c < vec.length; c++) {
                    float cc = vec[c];
                    if (cc > bestClassConf) { bestClassConf = cc; bestClass = c - 5; }
                }
            }
            float conf = objConf;
            if (hasClasses) conf = objConf * bestClassConf;
            if (conf < CONF_THRESHOLD) continue;

            Detection d = new Detection();
            // Many conversions produce x,y as center normalized coords. We assume normalized in [0..1]
            d.cx = x;
            d.cy = y;
            d.w = w;
            d.h = h;
            d.conf = conf;
            d.cls = bestClass;
            raw.add(d);
        }

        // 3) NMS
        List<Detection> keep = nms(raw, NMS_IOU);
        // Convert box coords from normalized to normalized (ensuring bounds)
        for (Detection d : keep) {
            d.cx = clamp(d.cx, 0f, 1f);
            d.cy = clamp(d.cy, 0f, 1f);
            d.w = clamp(d.w, 0f, 1f);
            d.h = clamp(d.h, 0f, 1f);
        }
        return keep;
    }

    // -------------------------
    // Converts a bbox (normalized cx,cy,w,h) to head center in absolute pixels
    // -------------------------
    private PointF bboxToHeadCenter(Detection d, int frameW, int frameH) {
        float left = (d.cx - d.w / 2f) * frameW;
        float top = (d.cy - d.h / 2f) * frameH;
        float right = (d.cx + d.w / 2f) * frameW;
        float bottom = (d.cy + d.h / 2f) * frameH;
        float headX = (left + right) / 2f;
        float headY = top + HEAD_Y_FACTOR * (bottom - top);
        // clamp
        headX = clamp(headX, 0f, frameW);
        headY = clamp(headY, 0f, frameH);
        return new PointF(headX, headY);
    }

    // -------------------------
    // Utility: convert ARGB_8888 bitmap to float32 ByteBuffer normalized [0..1]
    // (adapt if your model expects -1..1 or different order)
    // -------------------------
    private void convertBitmapToByteBuffer(Bitmap bitmap, ByteBuffer buffer) {
        if (buffer == null) return;
        buffer.rewind();
        int[] intValues = new int[inputWidth * inputHeight];
        bitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        int pixel = 0;
        for (int i = 0; i < inputWidth; ++i) {
            for (int j = 0; j < inputHeight; ++j) {
                final int val = intValues[pixel++];
                // ARGB -> R,G,B normalized 0..1
                float r = ((val >> 16) & 0xFF) / 255.0f;
                float g = ((val >> 8) & 0xFF) / 255.0f;
                float b = (val & 0xFF) / 255.0f;
                buffer.putFloat(r);
                buffer.putFloat(g);
                buffer.putFloat(b);
            }
        }
    }

    // -------------------------
    // Simple NMS (non-max suppression) on normalized boxes
    // -------------------------
    private List<Detection> nms(List<Detection> dets, float iouThreshold) {
        List<Detection> out = new ArrayList<>();
        Collections.sort(dets, new Comparator<Detection>() {
            @Override public int compare(Detection a, Detection b) { return Float.compare(b.conf, a.conf); }
        });
        boolean[] suppressed = new boolean[dets.size()];
        for (int i = 0; i < dets.size(); i++) {
            if (suppressed[i]) continue;
            Detection a = dets.get(i);
            out.add(a);
            for (int j = i + 1; j < dets.size(); j++) {
                if (suppressed[j]) continue;
                Detection b = dets.get(j);
                if (iou(a, b) > iouThreshold) suppressed[j] = true;
            }
        }
        return out;
    }

    // -------------------------
    // IoU assuming boxes in normalized center format (cx,cy,w,h)
    // -------------------------
    private float iou(Detection a, Detection b) {
        // convert to corners
        float aL = a.cx - a.w / 2f, aR = a.cx + a.w / 2f, aT = a.cy - a.h / 2f, aB = a.cy + a.h / 2f;
        float bL = b.cx - b.w / 2f, bR = b.cx + b.w / 2f, bT = b.cy - b.h / 2f, bB = b.cy + b.h / 2f;
        float interL = Math.max(aL, bL);
        float interT = Math.max(aT, bT);
        float interR = Math.min(aR, bR);
        float interB = Math.min(aB, bB);
        float interW = Math.max(0f, interR - interL);
        float interH = Math.max(0f, interB - interT);
        float interArea = interW * interH;
        float aArea = Math.max(0f, a.w) * Math.max(0f, a.h);
        float bArea = Math.max(0f, b.w) * Math.max(0f, b.h);
        float union = aArea + bArea - interArea;
        if (union <= 0f) return 0f;
        return interArea / union;
    }

    // -------------------------
    // Utility helpers & simple classes
    // -------------------------
    private float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    private float dist(PointF a, PointF b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float)Math.hypot(dx, dy);
    }

    // Detection in normalized coords
    private static class Detection {
        float cx, cy, w, h; // normalized 0..1
        float conf;
        int cls = -1;
    }

    // Target exposed to scoring pipeline
    private static class Target {
        PointF center;
        float confidence;
        float area;
        float score;
    }

    // -------------------------
    // Optional: cleanup delegates
    // -------------------------
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (nnApiDelegate != null) {
            nnApiDelegate.close();
            nnApiDelegate = null;
        }
    }

    // -------------------------
    // Debug helpers
    // -------------------------
    public void setDebug(boolean d) { debug = d; }
}
