package is.handsome.labs.doorbellcodelab;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.widget.ImageView;

import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import timber.log.Timber;

public class IoTActivity extends Activity {

    private static final String GPIO_PIN_BUTTON_NAME = "BCM21";
    private static final String GPIO_PIN_LED_NAME = "BCM6";
    private static final int INTERVAL_BETWEEN_BLINKS_MS = 1000;

    private Button button;

    private DoorbellCamera doorbellCamera;

    private Gpio ledGpio;
    private Handler handler = new Handler();
    private Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            // Exit if the GPIO is already closed
            if (ledGpio == null) {
                return;
            }

            try {
                // Step 2.3. Toggle the LED state
                ledGpio.setValue(!ledGpio.getValue());

                // Step 2.4. Schedule another event after delay.
                handler.postDelayed(blinkRunnable, INTERVAL_BETWEEN_BLINKS_MS);
            } catch (IOException e) {
                Timber.e(e, "Error on PeripheralIO API");
            }
        }
    };

    private Handler backgroundIoHandler;
    private HandlerThread backgroundTaskHandlerThread;

    private Handler cloudVisionHandler;
    private HandlerThread cloudVisionHandlerThread;

    private FirebaseEventService firebaseEventService;

    // Callback to receive captured camera image data
    private ImageReader.OnImageAvailableListener onImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    // Get the raw image bytes
                    Image image = reader.acquireLatestImage();
                    ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                    final byte[] imageBytes = new byte[imageBuf.remaining()];
                    imageBuf.get(imageBytes);
                    image.close();

                    onPictureTaken(imageBytes);
                }
            };

    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_iot);

        Timber.plant(new Timber.DebugTree());

        imageView = (ImageView) findViewById(R.id.image_photo);

        PeripheralManagerService service = new PeripheralManagerService();
        Timber.d("Available GPIO: " + service.getGpioList());

        try {
            // Init button
            button = new Button(
                    GPIO_PIN_BUTTON_NAME,
                    Button.LogicState.PRESSED_WHEN_LOW);
            button.setOnButtonEventListener(new Button.OnButtonEventListener() {
                @Override
                public void onButtonEvent(Button button, boolean pressed) {
                    if (pressed) {
                        Timber.d(GPIO_PIN_BUTTON_NAME + ": pressed. Take a picture.");
                        doorbellCamera.takePicture();
                    } else {
                        Timber.d(GPIO_PIN_BUTTON_NAME + ": unpressed");
                    }
                }
            });

            // Camera
            doorbellCamera = DoorbellCamera.getInstance();
            doorbellCamera.initializeCamera(this, backgroundIoHandler, onImageAvailableListener);

            // Led
            // Step 2.1. Create GPIO connection.
            ledGpio = service.openGpio(GPIO_PIN_LED_NAME);
            // Step 2.2. Configure as an output.
            ledGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            // Step 2.3. Repeat using a handler.
            handler.post(blinkRunnable);
        } catch (IOException e) {
            Timber.e(e);
        }

        startBackgroundThread();
        startCloudVisionThread();

        firebaseEventService = new FirebaseEventService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Button
        try {
            button.close();
        } catch (IOException e) {
            Timber.e(e, "Error while close " + GPIO_PIN_BUTTON_NAME + "button");
        }

        // Led
        // Step 2.4. Remove handler events on close.
        handler.removeCallbacks(blinkRunnable);

        // Step 2.5. Close the resource.
        if (ledGpio != null) {
            try {
                ledGpio.close();
            } catch (IOException e) {
                Timber.e(e, "Error on PeripheralIO API");
            }
        }
    }

    private void startBackgroundThread() {
        backgroundTaskHandlerThread = new HandlerThread("InputThread");
        backgroundTaskHandlerThread.start();
        backgroundIoHandler = new Handler(backgroundTaskHandlerThread.getLooper());
    }

    private void startCloudVisionThread() {
        cloudVisionHandlerThread = new HandlerThread("CloudThread");
        cloudVisionHandlerThread.start();
        cloudVisionHandler = new Handler(cloudVisionHandlerThread.getLooper());
    }

    private void onPictureTaken(byte[] imageBytes) {
        if (imageBytes != null) {
            // ...process the captured image...
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            imageView.setImageBitmap(bitmap);

            //TODO: check for what
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream);
            final byte[] imageBytesCompressed = byteArrayOutputStream.toByteArray();

            cloudVisionHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Timber.d("Sending image to cloud vision.");
                        Map<String, Float> annotations = CloudVisionUtils
                                .annotateImage(imageBytesCompressed);
                        firebaseEventService.pushEvent(imageBytesCompressed, annotations);
                    } catch (IOException e) {
                        Timber.e(e, "Exception while annotating image: ");
                    }
                }
            });
        }
    }
}
