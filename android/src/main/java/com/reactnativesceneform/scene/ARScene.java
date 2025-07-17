package com.reactnativesceneform.scene;

import static com.reactnativesceneform.utils.HelperFuncions.checkIsSupportedDeviceOrFinish;
import static com.reactnativesceneform.utils.HelperFuncions.saveBitmapToDisk;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.CamcorderProfile;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.filament.ColorGrading;
import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.Camera;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.SceneView;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.rendering.EngineInstance;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Renderer;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.BaseArFragment;
import com.reactnativesceneform.ModuleWithEmitter;
import com.reactnativesceneform.R;
import com.reactnativesceneform.utils.ModelManager;
import com.reactnativesceneform.utils.VideoRecorder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import uk.co.appoly.arcorelocation.LocationScene;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.DataOutputStream;
import org.json.JSONObject;
import org.json.JSONArray;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.ShapeFactory;
import android.media.Image;

@SuppressLint("ViewConstructor")
public class ARScene extends FrameLayout implements BaseArFragment.OnTapArPlaneListener, BaseArFragment.OnSessionConfigurationListener {
  public ThemedReactContext context;
  public ArFragment arFragment;
  public ModelManager mModelManager;
  private final List<Model> mChildren = new ArrayList<>();
  private final List<Anchor> mAnchors = new ArrayList<>();
  public LocationScene locationScene;
  private HostResolveMode viewMode = HostResolveMode.NONE;
  private Session.FeatureMapQuality mFeatureMapQuality;
  private boolean mHosting = false;
  private boolean mHosted = false;
  private Anchor mAnchorToHost;
  private VideoRecorder mVideoRecorder;
  private boolean mIsRecording = false;
  private boolean mDiscoverModeObjects = true;
  private ReadableArray mLocationMarkersData;
  private List<Anchor> mResolvingAnchors = new ArrayList<>();
  private final List<CompletableFuture<Void>> futures = new ArrayList<>();
  private boolean initialised = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long frameProcessingInterval = 4000L;
    private boolean isProcessingFrame = false;
    private final List<String> distances = new ArrayList<>();

    private final Runnable frameProcessingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isProcessingFrame) {
                disablePlaneRendererAndStartCapturing();
            }
            handler.postDelayed(this, frameProcessingInterval);
        }
    };

  private enum HostResolveMode {
    NONE,
    HOSTING,
    RESOLVING,
  }

  @RequiresApi(api = Build.VERSION_CODES.N)
  public ARScene(ThemedReactContext context) {
    super(context);
    this.context = context;
    mModelManager = new ModelManager(this);
    if(!initialised) {
      init();
    }
  }

  @SuppressLint("ShowToast")
  @RequiresApi(api = Build.VERSION_CODES.N)
  public void init() {
    if (!checkIsSupportedDeviceOrFinish((AppCompatActivity) Objects.requireNonNull(context.getCurrentActivity()))) {
      return;
    }
    inflate((AppCompatActivity) context.getCurrentActivity(), R.layout.activity_main, this);

    arFragment = (ArFragment) ((AppCompatActivity) context.getCurrentActivity()).getSupportFragmentManager().findFragmentById(R.id.arFragment);
    assert arFragment != null;

    arFragment.getArSceneView().getViewTreeObserver().addOnWindowFocusChangeListener( hasFocus -> {
      if(hasFocus){
        context.getCurrentActivity().getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
          Log.d("ArSceneView","Focused Changed to true");
          handler.post(() -> isProcessingFrame = false);
      }else{
          Log.d("ArSceneView","Focused Changed to false");
          handler.post(() -> isProcessingFrame = true);
      }
    });

    arFragment.setOnSessionConfigurationListener(this);
    arFragment.setOnTapArPlaneListener(this);
    arFragment.getArSceneView().setFrameRateFactor(SceneView.FrameRate.FULL);

    WritableMap event = Arguments.createMap();
    event.putBoolean("onSessionCreate", true);
    ModuleWithEmitter.sendEvent(context, ModuleWithEmitter.ON_SESSION_CREATE, event);
    arFragment.getArSceneView().getScene().addOnUpdateListener(this::onUpdateListener);

    mVideoRecorder = new VideoRecorder();
    mVideoRecorder.setSceneView(arFragment.getArSceneView());
    int orientation = getResources().getConfiguration().orientation;
    mVideoRecorder.setVideoQuality(CamcorderProfile.QUALITY_480P, orientation);

    initialised = true;
    startFrameProcessing();
  }

    public void sendLabelsToJS(List<String> labels) {
        WritableMap event = Arguments.createMap();
        WritableArray array = Arguments.createArray();

        for (String label : labels) {
            array.pushString(label);
        }

        event.putArray("distances", array);
        ModuleWithEmitter.sendEvent(context, ModuleWithEmitter.ON_ANCHOR_RESOLVE, event);
        distances.clear();
    }

   public void startFrameProcessing() {
        isProcessingFrame = false;
        handler.postDelayed(frameProcessingRunnable, frameProcessingInterval);
    }

    public void disablePlaneRendererAndStartCapturing(){
        ArSceneView view = arFragment.getArSceneView();
        view.getPlaneRenderer().setEnabled(false);


        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            captureFrameAndPostToApi(
                    "https://outline.roboflow.com/bocce-ball-2-eyrox/5?api_key=MQlv1Z6QF50wx2ZVwSvy",
                    view
            );
        }, 300);
    }

    public void stopFrameProcessing() {
        handler.removeCallbacks(frameProcessingRunnable);
    }

    public void captureFrameAndPostToApi(String apiUrl, ArSceneView view) {

        isProcessingFrame = true;

        if (view.getWindowToken() == null || !view.isAttachedToWindow()) {
            Log.w("ARSceneView", "View is not ready for PixelCopy. Skipping frame capture.");
            isProcessingFrame = false;
            return;
        }

        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);

        PixelCopy.request(view, bitmap, result -> {
            if (result == PixelCopy.SUCCESS) {
                try {
                    new Thread(() -> {
                        try {
                            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                            byte[] imageData = outputStream.toByteArray();
                            Log.d("ARSceneView", "Image generated successfully");
                            handler.post(() -> view.getPlaneRenderer().setEnabled(true));
                            sendImageToApi(apiUrl, imageData);
//                            uploadImageToApi("http://159.65.40.253:5021/api/upload", imageData);
                        } catch (Exception e) {
                            Log.e("ARSceneView", "Bitmap compress failed", e);
                            handler.post(() -> isProcessingFrame = false);
                        }
                    }).start();
                } catch (Exception e) {
                    Log.e("ARSceneView", "Error compressing bitmap", e);
                    handler.post(() -> isProcessingFrame = false);
                }
            } else {
                Log.e("ARSceneView", "PixelCopy failed: " + result);
                handler.post(() -> isProcessingFrame = false);
            }
        }, handler);
    }

    private void uploadImageToApi(String apiUrl, byte[] imageData) {
        new Thread(() -> {
            try {
                String boundary = "Boundary-" + System.currentTimeMillis();
                String lineEnd = "\r\n";
                String twoHyphens = "--";

                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
                outputStream.writeBytes(twoHyphens + boundary + lineEnd);
                outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"frame.png\"" + lineEnd);
                outputStream.writeBytes("Content-Type: image/png" + lineEnd);
                outputStream.writeBytes(lineEnd);
                outputStream.write(imageData);
                outputStream.writeBytes(lineEnd);
                outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                outputStream.flush();
                outputStream.close();

                int responseCode = connection.getResponseCode();
                String response = new java.util.Scanner(connection.getInputStream()).useDelimiter("\\A").next();
                Log.d("ARSceneView", "Response Code: " + responseCode);
                Log.d("ARSceneView", "Response Body: " + response);

                handler.post(() -> isProcessingFrame = false);

            } catch (Exception e) {
                Log.e("ARSceneView", "Failed to send image to API", e);
                handler.post(() -> isProcessingFrame = false);
            }
        }).start();
    }

    private void sendImageToApi(String apiUrl, byte[] imageData) {
        new Thread(() -> {
            try {
                String boundary = "Boundary-" + System.currentTimeMillis();
                String lineEnd = "\r\n";
                String twoHyphens = "--";

                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
                outputStream.writeBytes(twoHyphens + boundary + lineEnd);
                outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"frame.png\"" + lineEnd);
                outputStream.writeBytes("Content-Type: image/png" + lineEnd);
                outputStream.writeBytes(lineEnd);
                outputStream.write(imageData);
                outputStream.writeBytes(lineEnd);
                outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                outputStream.flush();
                outputStream.close();

                int responseCode = connection.getResponseCode();
                String response = new java.util.Scanner(connection.getInputStream()).useDelimiter("\\A").next();
                Log.d("ARSceneView", "Response Code: " + responseCode);
                Log.d("ARSceneView", "Response Body: " + response);

                JSONObject json = new JSONObject(response);
                JSONArray predictions = json.getJSONArray("predictions");

                if (predictions.length() <= 1) {
                    handler.post(() -> isProcessingFrame = false);
                } else {
                    float jackX = 0f;
                    float jackY = 0f;
                    float jackRadius = 0f;
                    boolean isJackLast = false;

                    for (int i = 0; i < predictions.length(); i++) {
                        JSONObject prediction = predictions.getJSONObject(i);
                        if (prediction.getString("class").equals("jack_ball")) {
                            jackX = (float) prediction.getDouble("x");
                            jackY = (float) prediction.getDouble("y");
                            float jackWidth = (float) prediction.getDouble("width");
                            float jackHeight = (float) prediction.getDouble("height");
                            jackRadius = Math.max(jackWidth, jackHeight) / 2.0f;
                            isJackLast = i == predictions.length() - 1;
                            break;
                        }
                    }

                    boolean isAllDistanceAdded = false;

                    if (jackX != 0f && jackY != 0f) {
                        for (int i = 0; i < predictions.length(); i++) {
                            JSONObject prediction = predictions.getJSONObject(i);
                            String className = prediction.getString("class");

                            if (!className.equals("jack_ball")) {
                                float x = (float) prediction.getDouble("x");
                                float y = (float) prediction.getDouble("y");
                                float playWidth = (float) prediction.getDouble("width");
                                float playHeight = (float) prediction.getDouble("height");
                                float playRadius = Math.max(playWidth, playHeight) / 2.0f;

                                boolean isLastPlayBowl = isJackLast ? i == predictions.length() - 2 : i == predictions.length() - 1;

                                float[] jackIntersectionResults = findCircleLineIntersection(jackX, jackY, jackRadius, jackX, jackY, x, y);
                                float[] playIntersectionResults = findCircleLineIntersection(x, y, playRadius, x, y, jackX, jackY);

                                if (jackIntersectionResults != null && playIntersectionResults != null) {
                                    boolean isDistanceAdded = getDistance(jackIntersectionResults[0], jackIntersectionResults[1], playIntersectionResults[0], playIntersectionResults[1], isLastPlayBowl);

                                    if (isDistanceAdded) {
                                        isAllDistanceAdded = true;
                                    }
                                }


                            }
                        }

                        if (!isAllDistanceAdded) {
                            handler.post(() -> isProcessingFrame = false);
                        }
                    } else {
                        handler.post(() -> isProcessingFrame = false);
                    }
                }
            } catch (Exception e) {
                Log.e("ARSceneView", "Failed to send image to API", e);
                handler.post(() -> isProcessingFrame = false);
            }
        }).start();
    }

    public float[] findCircleLineIntersection(
            float centerX, float centerY,
            float radius,
            float lineStartX, float lineStartY,
            float lineEndX, float lineEndY
    ) {
        // Calculate the differences in x and y between the line endpoints
        float dx = lineEndX - lineStartX;
        float dy = lineEndY - lineStartY;

        // Calculate the length of the line segment
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            // Line segment has zero length, return null
            return null;
        }

        // Normalize the direction vector
        float normalizedDx = dx / length;
        float normalizedDy = dy / length;

        // Calculate the intersection point on the circle's edge
        float intersectionX = centerX + normalizedDx * radius;
        float intersectionY = centerY + normalizedDy * radius;

        return new float[]{intersectionX, intersectionY};
    }

    public boolean getDistance(float x1, float y1, float x2, float y2, boolean isLast) {
        Frame frame = arFragment.getArSceneView().getArFrame();
        if (frame == null) return false;

        Anchor anchor1 = createAnchorNodeFromHit(x1, y1, frame);
        Anchor anchor2 = createAnchorNodeFromHit(x2, y2, frame);

        if (anchor1 == null || anchor2 == null) return false;


        new Handler(Looper.getMainLooper()).post(() -> {
            AnchorNode node1 = new AnchorNode(anchor1);
            AnchorNode node2 = new AnchorNode(anchor2);

            if (node1 == null || node2 == null) {
                Log.e("ARScene", "AnchorNode creation failed");
                return;
            }

            node1.setParent(arFragment.getArSceneView().getScene());
            node2.setParent(arFragment.getArSceneView().getScene());

            float distance = calculateDistanceBetweenNodes(node1, node2);
            String distanceInCM = measureDistanceOf2Points(distance, isLast);

            LocationMarker lMarker = new LocationMarker(this, anchor2);
            lMarker.setTitle(distanceInCM);
            lMarker.create();

            Vector3 pointA = node1.getWorldPosition();
            Vector3 pointB = node2.getWorldPosition();

            drawRoundedLine(pointA, pointB);
        });

        return true;
    }

    private float calculateDistanceBetweenNodes(AnchorNode node1, AnchorNode node2) {
        Vector3 pos1 = node1.getWorldPosition();
        Vector3 pos2 = node2.getWorldPosition();
        float dx = pos1.x - pos2.x;
        float dy = pos1.y - pos2.y;
        float dz = pos1.z - pos2.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String measureDistanceOf2Points(float distanceMeter, boolean isLast) {
        String distanceText = String.format("%.2f cm", distanceMeter * 100);
        distances.add(distanceText);
        if (isLast) {
            sendLabelsToJS(distances);
            stopFrameProcessing();
        }

        return distanceText;
    }

    private Anchor createAnchorNodeFromHit(float x, float y, Frame frame) {
        List<HitResult> hits = frame.hitTest(x, y);
        for (HitResult hit : hits) {
            if (hit.getTrackable() instanceof Plane) {
                Anchor anchor = hit.createAnchor();
                return anchor;
            }
        }
        return null;
    }

    public void drawRoundedLine(Vector3 point1, Vector3 point2) {
        Vector3 difference = Vector3.subtract(point2, point1);
        Vector3 direction = difference.normalized();
        float length = difference.length();

        Scene scene = arFragment.getArSceneView().getScene();

        // Create the renderable if not already
        MaterialFactory.makeOpaqueWithColor(context, new Color(android.graphics.Color.RED))
                .thenAccept(material -> {
                    ModelRenderable lineRenderable = ShapeFactory.makeCube(
                            new Vector3(0.01f, 0.01f, length), // small X/Y, length Z
                            Vector3.zero(), material);

                    // Center of the line
                    Vector3 position = Vector3.add(point1, point2).scaled(0.5f);

                    Node lineNode = new Node();
                    lineNode.setRenderable(lineRenderable);
                    lineNode.setWorldPosition(position);

                    // Point line toward point2
                    Quaternion rotation = Quaternion.lookRotation(direction, Vector3.up());
                    lineNode.setWorldRotation(rotation);

                    scene.addChild(lineNode);
                });
    }

  private void setOcclusionEnabled(boolean enabled){
    // TODO
  }

  public void onSessionConfiguration(Session session, Config config) {
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    }
    config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
    config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
  }

  public void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
    Anchor anchor = hitResult.createAnchor();

    LocationMarker lMarker = new LocationMarker(this, anchor);
    lMarker.setTitle("hello");
    lMarker.create();


//    mAnchors.add(anchor);
//    int index = mAnchors.indexOf(anchor);
//
//    WritableMap event = Arguments.createMap();
//    event.putBoolean("onTapPlane", true);
//    event.putString("planeId", ""+index);
//    ModuleWithEmitter.sendEvent(context, ModuleWithEmitter.ON_TAP_PLANE, event);
  }

  public void addObject(ReadableMap object){
    if(!mDiscoverModeObjects){
      return;
    }
    try{
      String name           = object.getString("name");//source
      String anchorId       = object.getString("anchorId");
      boolean isCloudAnchor = object.getBoolean("isCloudAnchor");

      if(isCloudAnchor){
        Log.i("Resolving anchor?", ""+isCloudAnchor);
        Anchor anchor = Objects.requireNonNull(arFragment.getArSceneView().getSession()).resolveCloudAnchor(anchorId);
        Log.i("Resolved anchor: ", anchor.getCloudAnchorId());
        Model model = new Model(this);
        model.setSource(name);
        model.fetchModel();
        model.setAnchor(anchor);
        model.place();
        mChildren.add(model);

        WritableMap event = Arguments.createMap();
        event.putBoolean("onAnchorResolve", true);
        event.putString("anchorId",         anchor.getCloudAnchorId());
        event.putString("pose",             anchor.getPose().toString());
        event.putString("trackingState",    anchor.getTrackingState().toString());
        event.putString("cloudState",       anchor.getCloudAnchorState().toString());
        event.putInt("HashCode",            anchor.hashCode());
        ModuleWithEmitter.sendEvent(context, ModuleWithEmitter.ON_ANCHOR_RESOLVE, event);
      }
      else{
        Log.i("TapPlane", "Plane tapped and putting model!");
        Anchor anchor = mAnchors.get(Integer.parseInt(anchorId));
        Model model = new Model(this);
        model.setSource(name);
        model.fetchModel();
        model.setAnchor(anchor);
        model.place();
        mChildren.add(model);
      }
    }
    catch(Exception e){
      Log.e("TapPlane", e.toString());
    }
  }

  public void setCurrentMode(boolean host){
    if(host){
      viewMode = HostResolveMode.HOSTING;
    }
    else{
      viewMode = HostResolveMode.RESOLVING;
    }
  }

  public void setPlaneVisibility(boolean visible){
    if(visible){
      if(arFragment != null && arFragment.getArSceneView().getSession() != null){
        arFragment.getArSceneView().getSession().getConfig().setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
      }
    }
    else{
      if(arFragment != null && arFragment.getArSceneView().getSession() != null){
        arFragment.getArSceneView().getSession().getConfig().setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
      }
    }
  }

  public void takeScreenshot(Promise promise) {
    ArSceneView view = arFragment.getArSceneView();
    final Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
      Bitmap.Config.ARGB_8888);
    final HandlerThread handlerThread = new HandlerThread("PixelCopier");
    handlerThread.start();
    PixelCopy.request(view, bitmap, (copyResult) -> {
      if (copyResult == PixelCopy.SUCCESS) {
        try {
          saveBitmapToDisk(context, bitmap, promise);
        } catch (IOException e) {
          promise.reject("Create screenshot error");
        }
      }
      handlerThread.quitSafely();
    }, new Handler(handlerThread.getLooper()));
  }

  public void resolveCloudAnchor(String anchorId){
    if(!mDiscoverModeObjects){
      return;
    }
    if(arFragment !=null && arFragment.getArSceneView().getSession() != null){
      Anchor anchor = Objects.requireNonNull(arFragment.getArSceneView().getSession()).resolveCloudAnchor(anchorId);
      mResolvingAnchors.add(anchor);
    }
  }

  public void hostCloudAnchor(int planeIndex) {
      startFrameProcessing();
      Scene scene = arFragment.getArSceneView().getScene();

      // Remove all children safely
      List<Node> children = new ArrayList<>(scene.getChildren());

      for (Node node : children) {
          node.setRenderable(null);
      }
//    if(!mHosting){
//      if(arFragment !=null && arFragment.getArSceneView().getSession() != null) {
//        Anchor localAnchor = mAnchors.get(planeIndex);
//        if (localAnchor != null) {
//          mAnchorToHost = Objects.requireNonNull(arFragment.getArSceneView().getSession()).hostCloudAnchorWithTtl(localAnchor, 365);
//          mHosting = true;
//        }
//      }
//    }
  }

  public void startVideoRecording(Promise promise) {
    if(arFragment != null && arFragment.getArSceneView().getSession() != null && !mIsRecording){
      mIsRecording =  mVideoRecorder.onToggleRecord();
      WritableMap event = Arguments.createMap();
      if(mIsRecording){
        event.putBoolean("recording", true);
        promise.resolve(event);
      }
      else{
        promise.reject("recording", "false");
      }
    }
  }

  public void stopVideoRecording(Promise promise) {
    if(arFragment != null && arFragment.getArSceneView().getSession() != null && mIsRecording){
      mIsRecording = mVideoRecorder.onToggleRecord();
      String videoPath = mVideoRecorder.getVideoPath().getAbsolutePath();

      WritableMap event = Arguments.createMap();
      event.putString("path", videoPath);
      promise.resolve(event);
    }
  }

  public void setDiscoverMode(boolean objects) {
    mDiscoverModeObjects = objects;
    if(objects){
      if(locationScene != null){
        locationScene.clearMarkers();
      }
      setOcclusionEnabled(true);
    }
    else{
      List<Node> children  = new ArrayList<>(arFragment.getArSceneView().getScene().getChildren());
      for(Node node : children){
        if(node instanceof AnchorNode){
          if(((AnchorNode) node).getAnchor() != null){
            Objects.requireNonNull(((AnchorNode) node).getAnchor()).detach();
          }
        }
        if(!(node instanceof Camera)){
          node.setParent(null);
        }
      }
      try {
        mChildren.clear();
        mAnchors.clear();
      }
      catch(Exception e){
        Log.e("ViewMode", e.toString());
      }
      redrawLocationMarkers();
    }
  }

  public void setLocationMarkers(ReadableArray data) {
    mLocationMarkersData = data;
    redrawLocationMarkers();
  }

  private void redrawLocationMarkers(){
    if(mDiscoverModeObjects){
      Log.d("LocationMarkers", "Returning due misconfiguration");
      return;
    }
    setOcclusionEnabled(false);
//    if(mLocationMarkersData != null){
//      for(int index = 0; index < mLocationMarkersData.size(); index++){
//        ReadableMap element = mLocationMarkersData.getMap(index);
//        LocationMarker lMarker = new LocationMarker(this);
//        assert element != null;
//        lMarker.setTitle(element.getString("title"));
//        lMarker.setType(element.getBoolean("isAnchor"));
//        lMarker.setPos(element.getDouble("lng"), element.getDouble("lat"));
//        lMarker.create();
//      }
//      //Log.d("LocationMarkers", "Created " + locationScene.mLocationMarkers.size() + " markers");
//    }
    if (locationScene != null) {
      locationScene.refreshAnchors();
    }
  }

  private void onUpdateListener(FrameTime frameTime) {
    try {
      Frame frame = arFragment.getArSceneView().getArFrame();
      if(frame == null){
        return;
      }
      for(Model model : mChildren){
        model.onUpdate(frameTime);
      }
      com.google.ar.core.Camera camera = frame.getCamera();
      if(camera.getTrackingState() == TrackingState.TRACKING && viewMode == HostResolveMode.HOSTING) {
        Session.FeatureMapQuality featureMapQuality = Objects.requireNonNull(arFragment.getArSceneView().getSession()).estimateFeatureMapQualityForHosting(frame.getCamera().getPose());
        if (mFeatureMapQuality != featureMapQuality) {
          int quality;
          if (featureMapQuality == Session.FeatureMapQuality.INSUFFICIENT) {
            quality = 0;
          } else if (featureMapQuality == Session.FeatureMapQuality.SUFFICIENT) {
            quality = 1;
          } else {
            quality = 2;
          }
          WritableMap event = Arguments.createMap();
          event.putBoolean("onFeatureMapQualityChange", true);
          event.putInt("quality", quality);
          ModuleWithEmitter.sendEvent(context, ModuleWithEmitter.ON_FEATURE_MAP_QUALITY_CHANGE, event);
          mFeatureMapQuality = featureMapQuality;
        }
      }
      if(mHosting && !mHosted){
        Anchor.CloudAnchorState anchorState = mAnchorToHost.getCloudAnchorState();
        if(!anchorState.isError() && anchorState == Anchor.CloudAnchorState.SUCCESS){
          mHosted = true;
          WritableMap event = Arguments.createMap();
          event.putBoolean("onAnchorHost", true);
          event.putString("anchorId", mAnchorToHost.getCloudAnchorId());
          ModuleWithEmitter.sendEvent(context, ModuleWithEmitter.ON_ANCHOR_HOST, event);
        }
      }

      /*
      for(Plane plane : frame.getUpdatedTrackables(Plane.class)) {
        //addObjectModel(Uri.parse("pluto.sfb"));
        break;
      }
       */

      if (locationScene == null) {
        locationScene = new LocationScene(this.context.getCurrentActivity(), arFragment.getArSceneView());
        redrawLocationMarkers();
      }
      if (locationScene != null) {
        locationScene.processFrame(frame);
      }
    }
    catch(Exception e){
      Log.e("onUpdateListener", e.toString());
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    List<Node> children  = new ArrayList<>(arFragment.getArSceneView().getScene().getChildren());
    for(Node node : children){
      if(node instanceof AnchorNode){
        if(((AnchorNode) node).getAnchor() != null){
          Objects.requireNonNull(((AnchorNode) node).getAnchor()).detach();
        }
      }
      if(!(node instanceof Camera)){
        node.setParent(null);
      }
    }
    mResolvingAnchors.clear();
    mAnchors.clear();
    if(mIsRecording){
      mVideoRecorder.onToggleRecord();
    }
    ((AppCompatActivity) Objects.requireNonNull(context.getCurrentActivity())).getSupportFragmentManager().beginTransaction().remove(arFragment).commitAllowingStateLoss();
    Thread threadPause = new Thread() {
      @Override
      public void run() {
        try {
          sleep(100);
          Objects.requireNonNull(arFragment.getArSceneView().getSession()).pause();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          threadPause.start();
          sleep(500);
          Objects.requireNonNull(arFragment.getArSceneView().getSession()).close();
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          Log.e("END_", "Finish");
        }
      }
    };
    thread.start();
  }

}
