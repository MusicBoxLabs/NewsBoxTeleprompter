package br.com.newsboxteleprompter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContentValues;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.*;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Motor de camera e gravacao.
 *
 * Vem do TiltRecords quase intacto — e codigo ja rodado em campo, e
 * mexer nele so pelo prazer de mexer seria trocar o certo pelo duvidoso.
 * As diferencas sao tres, todas deliberadas:
 *
 *   1) a camera FRONTAL e o padrao (no prompter voce se olha);
 *   2) a falta do receptor USB-C vira AVISO, nao trava;
 *   3) o video salva em Filmes/NewsBoxTeleprompter.
 *
 * O resto — resolucao maxima, pista de orientacao, MP4 sem filtro,
 * MediaStore para aparecer na Galeria — e o original.
 */
final class RecordingEngine {
    interface Listener { void status(String s); void input(String s); void ready();
                         void recordingStarted(); void finished(String p); void error(String e);
                         /** Recado nao-fatal: aparece em amarelo e nao impede gravar. */
                         void aviso(String s); }
    private final Context context; private final TextureView preview; private final Listener listener;
    private CameraDevice camera; private CameraCaptureSession session; private HandlerThread thread; private Handler handler;
    private Size size; private volatile boolean recording; private MediaRecorder recorder;
    private int sensorOrientation;
    private Uri outputUri; private ParcelFileDescriptor outputDescriptor;
    // No prompter a frontal e o padrao: quem le o texto esta olhando
    // para a propria tela, e e essa imagem que vai para o video.
    private int lensFacing = CameraCharacteristics.LENS_FACING_FRONT;
    private boolean openingCamera;
    private volatile boolean starting;
    private volatile boolean ready;

    private AudioDeviceCallback vigiaAudio;

    RecordingEngine(Context c, TextureView p, Listener l) {
        context=c; preview=p; listener=l;
        vigiarEntradasDeAudio();
    }

    /**
     * Fica de olho no que entra e sai da porta USB-C.
     *
     * Antes o app perguntava UMA vez, ao abrir: quem plugava o
     * receptor depois tinha que fechar e abrir o aplicativo, e o
     * aviso amarelo continuava mentindo na tela. O Android avisa
     * sozinho quando um dispositivo de audio entra ou sai — e so
     * escutar, e o aviso se corrige no instante em que o cabo encosta.
     */
    private void vigiarEntradasDeAudio() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            vigiaAudio = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] novos)   { avisarEntrada(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] saidos) { avisarEntrada(); }
            };
            am.registerAudioDeviceCallback(vigiaAudio, new Handler(android.os.Looper.getMainLooper()));
        } catch (Exception ignored) { }
    }

    private void avisarEntrada() {
        boolean usb = hasUsbInput();
        listener.input(audioInputLabel());
        listener.aviso(usb ? null : "Sem receptor USB-C — vai gravar com o microfone do aparelho");
    }
    boolean isRecording() { return recording; }
    boolean isStarting() { return starting; }
    boolean isReady() { return ready; }

    void openPreview() {
        thread = new HandlerThread("NewsBoxCamera"); thread.start(); handler = new Handler(thread.getLooper());
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){ open(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){ return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s){}
        });
        if (preview.isAvailable()) open();
    }

    @SuppressLint("MissingPermission") private synchronized void open() {
        if (openingCamera || camera != null) return;
        ready=false;
        openingCamera=true;
        try {
            CameraManager cm=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE); String id=null;
            for(String candidate:cm.getCameraIdList()) if(cm.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING)==lensFacing){id=candidate;break;}
            if(id==null) throw new IllegalStateException(lensFacing == CameraCharacteristics.LENS_FACING_BACK ? "Câmera traseira não encontrada" : "Câmera frontal não encontrada");
            CameraCharacteristics cc=cm.getCameraCharacteristics(id); sensorOrientation=cc.get(CameraCharacteristics.SENSOR_ORIENTATION); android.hardware.camera2.params.StreamConfigurationMap map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] options=map.getOutputSizes(MediaRecorder.class); size=largest(options); preview.post(this::ajustarPrevia); listener.status((lensFacing == CameraCharacteristics.LENS_FACING_BACK ? "Câmera traseira" : "Câmera frontal")+" — vídeo em "+size.getWidth()+" × "+size.getHeight()); listener.input(audioInputLabel());
            cm.openCamera(id,new CameraDevice.StateCallback(){ public void onOpened(CameraDevice c){openingCamera=false;camera=c; previewSession();} public void onDisconnected(CameraDevice c){openingCamera=false;ready=false;c.close();if(camera==c)camera=null;} public void onError(CameraDevice c,int e){openingCamera=false;ready=false;c.close();if(camera==c)camera=null; listener.error("Não foi possível abrir a câmera (erro "+e+")");}},handler);
        } catch(Exception e){openingCamera=false;listener.error("Erro ao preparar a câmera: "+e.getMessage());}
    }
    private Size largest(Size[] choices) { Size best=choices[0]; for(Size s:choices) if((long)s.getWidth()*s.getHeight()>(long)best.getWidth()*best.getHeight()) best=s; return best; }
    private String audioInputLabel() {
        AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_INPUTS)) if(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE || d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET) return "Áudio: receptor USB-C detectado — será usado na gravação";
        return "Áudio: microfone do aparelho (nenhum USB-C detectado)";
    }
    private void previewSession() {
        if (camera == null) { listener.error("A câmera ainda está sendo preparada. Aguarde um instante e tente novamente."); return; }
        try { SurfaceTexture t=preview.getSurfaceTexture(); t.setDefaultBufferSize(size.getWidth(),size.getHeight()); Surface s=new Surface(t);
            camera.createCaptureSession(Collections.singletonList(s),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession cs){session=cs; try{CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); b.addTarget(s); cs.setRepeatingRequest(b.build(),null,handler); ready=true; listener.ready();}catch(Exception e){ready=false;listener.error(e.getMessage());}}public void onConfigureFailed(CameraCaptureSession cs){ready=false;listener.error("Falha ao criar a prévia");}},handler);
        } catch(Exception e){listener.error("Falha na prévia: "+e.getMessage());}
    }
    void start() { if(recording || starting || !ready)return; starting=true; ready=false; handler.post(this::startInternal); }
    private void startInternal() {
        try {
            if (camera == null) { failStart("A câmera foi fechada; aguarde ela voltar antes de tocar em REC."); return; }
            // Aqui esta a diferenca para o TiltRecords: sem receptor USB-C a
            // gravacao NAO e recusada. Quem comprou o NEWS BOX pode nao ter o
            // Armer, e travar o botao deixaria o produto sem gravar. O aviso
            // amarelo aparece na tela e o microfone do aparelho assume.
            if (!hasUsbInput()) listener.aviso("Sem receptor USB-C — gravando com o microfone do aparelho");
            else listener.aviso(null);
            if(session!=null){ try{session.stopRepeating();session.abortCaptures();}catch(Exception ignored){} session.close();session=null; }
            createPublicOutput();
            recorder=new MediaRecorder(); recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(outputDescriptor.getFileDescriptor()); recorder.setVideoEncodingBitRate(videoBitrate()); recorder.setVideoFrameRate(30); recorder.setVideoSize(size.getWidth(),size.getHeight()); recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setAudioEncodingBitRate(128000); recorder.setAudioSamplingRate(48000); recorder.setOrientationHint(videoOrientation()); recorder.prepare();
            SurfaceTexture t=preview.getSurfaceTexture(); t.setDefaultBufferSize(size.getWidth(),size.getHeight()); Surface p=new Surface(t); Surface r=recorder.getSurface();
            camera.createCaptureSession(Arrays.asList(p,r),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession cs){try{session=cs; CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD); b.addTarget(p); b.addTarget(r); cs.setRepeatingRequest(b.build(),null,handler); recorder.start(); recording=true; starting=false; listener.recordingStarted();}catch(Exception e){failStart("Não foi possível iniciar: "+e.getMessage());}} public void onConfigureFailed(CameraCaptureSession c){failStart("Falha ao iniciar a gravação");}},handler);
        } catch(Exception e){ failStart("Erro ao preparar vídeo: "+e.getMessage()); }
    }
    private void failStart(String message) {
        starting=false; recording=false; ready=false;
        try{if(recorder!=null){recorder.reset();recorder.release();recorder=null;}}catch(Exception ignored){}
        try{if(outputDescriptor!=null){outputDescriptor.close();outputDescriptor=null;}}catch(Exception ignored){}
        try{if(outputUri!=null){context.getContentResolver().delete(outputUri,null,null);outputUri=null;}}catch(Exception ignored){}
        listener.error(message);
        if(camera!=null && preview.isAvailable()) previewSession();
    }
    /* A chamada MIC deixa a escolha da rota para o Android; a verificação USB evita salvar vídeo com o microfone interno por engano. */
    private boolean hasUsbInput(){ AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE); for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_INPUTS)) if(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET)return true; return false; }
    private int videoBitrate(){ long pixels=(long)size.getWidth()*size.getHeight(); return pixels>=8000000?28000000:pixels>=2000000?14000000:8000000; }
    /*
     * v1.1 — endireita a previa conforme o giro da tela (e o metodo
     * configureTransform do exemplo Camera2 do Google, em portugues).
     * Em pe nao faz nada: devolve a matriz neutra e a previa fica
     * exatamente como sempre foi. Deitado, troca largura por altura,
     * preenche a tela sem esticar e gira a imagem para o lado certo.
     * So mexe no que se VE; o arquivo gravado continua sendo endireitado
     * pela pista de orientacao logo abaixo.
     */
    void ajustarPrevia() {
        if (size == null) return;
        int w = preview.getWidth(), h = preview.getHeight();
        if (w == 0 || h == 0) return;
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        Matrix m = new Matrix();
        float cx = w / 2f, cy = h / 2f;
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RectF vista = new RectF(0, 0, w, h);
            RectF quadro = new RectF(0, 0, size.getHeight(), size.getWidth());
            quadro.offset(cx - quadro.centerX(), cy - quadro.centerY());
            m.setRectToRect(vista, quadro, Matrix.ScaleToFit.FILL);
            float escala = Math.max((float) h / size.getHeight(), (float) w / size.getWidth());
            m.postScale(escala, escala, cx, cy);
            m.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) {
            m.postRotate(180, cx, cy);
        }
        preview.setTransform(m);
    }
    /* O sensor filma deitado; a pista de orientação faz Galeria, CapCut e redes exibirem o MP4 em pé. */
    private int videoOrientation() {
        int rotation=((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
        return lensFacing==CameraCharacteristics.LENS_FACING_FRONT ? (sensorOrientation+degrees)%360 : (sensorOrientation-degrees+360)%360;
    }
    private void createPublicOutput() throws Exception {
        String name="NewsBox_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".mp4";
        ContentValues values=new ContentValues(); values.put(MediaStore.Video.Media.DISPLAY_NAME,name); values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); values.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+File.separator+"NewsBoxTeleprompter"); values.put(MediaStore.Video.Media.IS_PENDING,1);
        outputUri=context.getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
        if(outputUri==null) throw new IllegalStateException("Não foi possível criar o vídeo na Galeria");
        outputDescriptor=context.getContentResolver().openFileDescriptor(outputUri,"w");
        if(outputDescriptor==null) throw new IllegalStateException("Não foi possível abrir o arquivo do vídeo");
    }
    void stop(){ handler.post(()->{ try{ ready=false; if(session!=null){try{session.stopRepeating();session.abortCaptures();}catch(Exception ignored){}session.close();session=null;} if(recorder!=null){recorder.stop();recorder.reset();recorder.release();recorder=null;} if(outputDescriptor!=null){outputDescriptor.close();outputDescriptor=null;} ContentValues done=new ContentValues();done.put(MediaStore.Video.Media.IS_PENDING,0);context.getContentResolver().update(outputUri,done,null,null); outputUri=null; recording=false; listener.finished("Galeria > Álbuns > NewsBoxTeleprompter"); previewSession();}catch(Exception e){recording=false;starting=false;listener.error("Erro ao salvar: "+e.getMessage());} }); }
    void switchCamera(){ handler.post(()->{ try { if(session!=null){session.close();session=null;} if(camera!=null){camera.close();camera=null;} lensFacing = lensFacing == CameraCharacteristics.LENS_FACING_BACK ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK; open(); } catch(Exception e){listener.error("Não foi possível trocar a câmera: "+e.getMessage());} }); }
    void close(){
        try {
            if (vigiaAudio != null) {
                AudioManager am=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                am.unregisterAudioDeviceCallback(vigiaAudio);
                vigiaAudio = null;
            }
        } catch(Exception ignored){}
        if(recording)stop(); try{if(session!=null)session.close();if(camera!=null)camera.close();}catch(Exception ignored){} if(thread!=null)thread.quitSafely(); }
}
