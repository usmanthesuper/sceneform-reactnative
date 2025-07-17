# 📱 ARScene (React Native + ARCore + Sceneform)

`ARScene` is a powerful Android view designed for integration with React Native apps, leveraging ARCore and Sceneform to render interactive and immersive AR scenes.

This class manages everything from placing 3D models, measuring distances, capturing frames for image detection, to hosting/resolving cloud anchors.

---

## 🔧 Features

* 📷 Capture frames and send to an external image detection API
* 🧠 Automatically identify objects and compute 3D distances
* 📏 Draw 3D lines and show distance labels
* 📍 Host and resolve cloud anchors (Google Cloud Anchors)
* 🌐 Location-based AR marker support (`LocationScene`)
* 🎥 Record and save AR videos
* 🖼️ Take screenshots of current AR session
* ↻ React Native bridge event system for communication between JS and native code

---

## 📁 File Location

This class is located at:

```
com/reactnativesceneform/scene/ARScene.java
```

---

## 🏗️ Dependencies

* `ARCore` (Google)
* `Sceneform` (via `com.gorisse.thomas.sceneform`)
* `React Native` bridge
* `LocationScene` (AR geo-location integration)
* `PixelCopy` for frame capturing
* `VideoRecorder` (custom utility class)

---

## 🚀 Lifecycle

### Initialization

* Checks device ARCore support
* Inflates `activity_main` layout containing `ArFragment`
* Sets listeners for session config and plane taps
* Starts periodic frame capture for object detection

### Frame Processing

Every 4 seconds:

* Captures a bitmap from `ArSceneView`
* Sends it to an object detection API
* Identifies objects like "jack\_ball" and others
* Calculates and draws 3D distances between them

---

## ↻ JS-Native Communication

Events emitted to JavaScript:

| Event Name                      | Description                              |
| ------------------------------- | ---------------------------------------- |
| `ON_SESSION_CREATE`             | Triggered when AR session is initialized |
| `ON_ANCHOR_RESOLVE`             | Triggered when cloud anchor is resolved  |
| `ON_ANCHOR_HOST`                | Triggered when cloud anchor is hosted    |
| `ON_FEATURE_MAP_QUALITY_CHANGE` | Emits current tracking quality           |

---

## 🧠 Cloud Anchor Support

* Hosts anchors to the cloud via `hostCloudAnchor()`
* Resolves anchors using `resolveCloudAnchor(anchorId)`
* Supports feature map quality feedback during hosting

---

## ✨ Public Methods

### Measurement

```java
getDistance(x1, y1, x2, y2, isLast)
```

Creates anchors at 2D screen points and computes the 3D distance, also draws a line and label.

---

### Frame Capture

```java
captureFrameAndPostToApi(apiUrl, view)
```

Captures the current frame and POSTs it to an image recognition API.

---

### AR Session Management

```java
setCurrentMode(hosting: boolean)
setPlaneVisibility(visible: boolean)
startFrameProcessing()
stopFrameProcessing()
```

---

### Visual Features

```java
drawRoundedLine(Vector3 pointA, Vector3 pointB)
takeScreenshot(Promise promise)
startVideoRecording(Promise promise)
stopVideoRecording(Promise promise)
```

---

## 🧪 Object Detection Integration

You can customize the object detection API used in:

```java
sendImageToApi(apiUrl, imageData)
```

Expected API Response (Roboflow/YOLO-like):

```json
{
  "predictions": [
    { "class": "jack_ball", "x": 100, "y": 120, "width": 30, "height": 30 },
    { "class": "red_ball", "x": 200, "y": 140, "width": 40, "height": 40 }
  ]
}
```

---

## 📍 Location Markers

* Uses `LocationScene` for placing markers using GPS
* Markers can be refreshed dynamically via:

```java
setLocationMarkers(ReadableArray data)
setDiscoverMode(boolean)
```

---

## 🛩️ Cleanup

AR session, fragments, and anchors are detached and released in:

```java
onDetachedFromWindow()
```

---

## ⚠️ Notes

* Requires Android **Nougat (API 24+)** or above
* Must be used inside a `FragmentActivity`
* Avoid using with `Fragments` in React Native navigation (native view lifecycle needs managing)

---

## 📷 Example Screenshot (to be added)

---

## 📚 Related Files

* `ModelManager.java`: Manages loading/rendering 3D models
* `LocationMarker.java`: For drawing markers in AR
* `VideoRecorder.java`: For screen recording
* `ModuleWithEmitter.java`: Sends JS bridge events
* `HelperFunctions.java`: Bitmap saving and device check utilities

---

## 🤝 Contributions

Feel free to fork and customize the `ARScene` view for your AR apps.
