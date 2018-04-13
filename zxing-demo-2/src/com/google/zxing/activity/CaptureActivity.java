/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.zxing.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.zxing.Result;
import com.google.zxing.camera.CameraManager;
import com.google.zxing.client.result.ResultParser;
import com.google.zxing.decode.BitmapDecoder;
import com.google.zxing.decode.DecodeThread;
import com.google.zxing.utils.BeepManager;
import com.google.zxing.utils.BitmapUtils;
import com.google.zxing.utils.CaptureActivityHandler;
import com.google.zxing.utils.InactivityTimer;
import com.jinlin.zxing.R;
import com.jinlin.zxing.ResultActivity;

import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

/**
 * This activity opens the camera and does the actual scanning on a background
 * thread. It draws a viewfinder to help the user place the barcode correctly,
 * shows feedback as the image processing is happening, and then overlays the
 * results when a scan is successful.
 * 
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback,OnClickListener {

	private static final String TAG = CaptureActivity.class.getSimpleName();
	
	private static final int REQUEST_CODE = 100;

	private static final int PARSE_BARCODE_FAIL = 300;
	
	private static final int PARSE_BARCODE_SUC = 200;

	private CameraManager cameraManager;
	private CaptureActivityHandler handler;
	private InactivityTimer inactivityTimer;
	private BeepManager beepManager;

	private SurfaceView scanPreview = null;
	private RelativeLayout scanContainer;
	private RelativeLayout scanCropView;
	private ImageView scanLine;

	private Rect mCropRect = null;
	
	private boolean isFlashlightOpen;

	//自定义添加时间
	//private TextView time1 = (TextView) findViewById(R.id.tv_time1);
	SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
	//获取本地Mac地址
	String strMacadds = new String();
	/**
	 * 图片的路径
	 */
	private String photoPath;

	private Handler mHandler = new MyHandler(this);

	//测试MyHandler数据的传出
	//MyHandler volumnContent ;

	static class MyHandler extends Handler {

		private WeakReference<Activity> activityReference;
		public String str;

		public MyHandler(Activity activity) {
			activityReference = new WeakReference<Activity>(activity);
		}

		@Override
		public void handleMessage(Message msg) {

			switch (msg.what) {
				case PARSE_BARCODE_SUC: // 解析图片成功
					Toast.makeText(activityReference.get(),
							"解析成功，结果为：" + msg.obj, Toast.LENGTH_SHORT).show();
					break;
				case PARSE_BARCODE_FAIL:// 解析图片失败
					Toast.makeText(activityReference.get(), "解析图片失败",
							Toast.LENGTH_SHORT).show();
				break;

				default:
					break;
			}

			super.handleMessage(msg);
		}

	}

	
	public Handler getHandler() {
		return handler;
	}

	public CameraManager getCameraManager() {
		return cameraManager;

	}

	private boolean isHasSurface = false;

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		getActionBar().setDisplayHomeAsUpEnabled(true);
		Window window = getWindow();
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.activity_capture);

		scanPreview = (SurfaceView) findViewById(R.id.capture_preview);
		scanContainer = (RelativeLayout) findViewById(R.id.capture_container);
		scanCropView = (RelativeLayout) findViewById(R.id.capture_crop_view);
		scanLine = (ImageView) findViewById(R.id.capture_scan_line);

		inactivityTimer = new InactivityTimer(this);
		beepManager = new BeepManager(this);

		TranslateAnimation animation = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT,
				0.9f);
		animation.setDuration(4500);
		animation.setRepeatCount(-1);
		animation.setRepeatMode(Animation.RESTART);
		scanLine.startAnimation(animation);
		
		findViewById(R.id.capture_flashlight).setOnClickListener(this);
		findViewById(R.id.capture_scan_photo).setOnClickListener(this);
		/*获取Mac地址*/
		strMacadds = getMac();
	}



	@Override
	protected void onResume() {
		super.onResume();

		// CameraManager must be initialized here, not in onCreate(). This is
		// necessary because we don't
		// want to open the camera driver and measure the screen size if we're
		// going to show the help on
		// first launch. That led to bugs where the scanning rectangle was the
		// wrong size and partially
		// off screen.
		cameraManager = new CameraManager(getApplication());

		handler = null;

		if (isHasSurface) {
			// The activity was paused but not stopped, so the surface still
			// exists. Therefore
			// surfaceCreated() won't be called, so init the camera here.
			initCamera(scanPreview.getHolder());
		} else {
			// Install the callback and wait for surfaceCreated() to init the
			// camera.
			scanPreview.getHolder().addCallback(this);
		}


		inactivityTimer.onResume();
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int id = item.getItemId();
		switch (id) {
		case android.R.id.home:
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);

	}

	@Override
	protected void onPause() {
		if (handler != null) {
			handler.quitSynchronously();
			handler = null;
		}
		inactivityTimer.onPause();
		beepManager.close();
		cameraManager.closeDriver();
		if (!isHasSurface) {
			scanPreview.getHolder().removeCallback(this);
		}
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		inactivityTimer.shutdown();
		super.onDestroy();
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		if (holder == null) {
			Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
		}
		if (!isHasSurface) {
			isHasSurface = true;
			initCamera(holder);
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		isHasSurface = false;
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

	}

	/**
	 * A valid barcode has been found, so give an indication of success and show
	 * the results.
	 * 
	 * @param rawResult
	 *            The contents of the barcode.
	 * 
	 * @param bundle
	 *            The extras
	 */
	public void handleDecode(Result rawResult, Bundle bundle) {
		inactivityTimer.onActivity();
		beepManager.playBeepSoundAndVibrate();

		bundle.putInt("width", mCropRect.width());
		bundle.putInt("height", mCropRect.height());
		bundle.putString("result", rawResult.getText());

		/*测试二维码的内容。*/
		Log.i("CaptureActivity","value:"+rawResult.getText());

		/*测试二维码读取时间。*/
		//simpleDateFormat.format(getdate());
		Log.i("CaptureActivity", "value:" + simpleDateFormat.format(getdate()));

		/*测试读取MAC地址。*/
		Log.i("CaptureActivity", "value:" + strMacadds);
		toPost(rawResult.getText(), strMacadds);

		startActivity(new Intent(CaptureActivity.this, ResultActivity.class).putExtras(bundle));
	}

//获取系统时间
	private static Date getdate(){
		//SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
		Date date = new Date(System.currentTimeMillis());

		return date;
	}
//获取本地Mac地址
		/**
		 * 获取手机的MAC地址
		 *
		 * @return
		 */

		public static String getMac() {
			String str = "";
			String macSerial = "";
			try {
				Process pp = Runtime.getRuntime().exec(
						"cat /sys/class/net/wlan0/address ");
				InputStreamReader ir = new InputStreamReader(pp.getInputStream());
				LineNumberReader input = new LineNumberReader(ir);

				for (; null != str;) {
					str = input.readLine();
					if (str != null) {
						macSerial = str.trim();// 去空格
						break;
					}
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			if (macSerial == null || "".equals(macSerial)) {
				try {
					return loadFileAsString("/sys/class/net/eth0/address")
							.toUpperCase().substring(0, 17);
				} catch (Exception e) {
					e.printStackTrace();

				}

			}
			return macSerial;
		}

		public static String loadFileAsString(String fileName) throws Exception {
			FileReader reader = new FileReader(fileName);
			String text = loadReaderAsString(reader);
			reader.close();
			return text;
		}

		public static String loadReaderAsString(Reader reader) throws Exception {
			StringBuilder builder = new StringBuilder();
			char[] buffer = new char[4096];
			int readLength = reader.read(buffer);
			while (readLength >= 0) {
				builder.append(buffer, 0, readLength);
				readLength = reader.read(buffer);
			}
			return builder.toString();
		}

//截止。

	//post方法
	public void toPost(String str1, String str2){

		Log.i("aaaaaa","aaaaaaaa");
		final String content = str1;
		final String userid = str2;

		Thread t = new Thread(){
			@Override
			public void run() {
				//提交的数据需要进行URL编码，字母和数字编码后都不变
				//String path = "http://192.168.164.129/learn-slim/public/";
				String path = "http://192.168.89.148/learn-slim/public/";
				Log.i("aaaaaa","ccccccc");
				/*第三种方式*/
				final ByteArrayOutputStream bos;
				try {
					Log.i("aaaaaa","ddddddd");
					String data = "content="+content + "&date=" + simpleDateFormat.format(getdate()) + "&userid=" + userid;
					HttpURLConnection conn = (HttpURLConnection) new URL(path).openConnection();
					conn.setReadTimeout(5000);
					conn.setRequestMethod("POST");
					conn.setDoOutput(true);
					conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
//					conn.setRequestProperty("Content-Length", data.length() + "");
					conn.setConnectTimeout(5000);
					OutputStream out = conn.getOutputStream();
					out.write(data.getBytes());
					if (conn.getResponseCode() == 200) {
						InputStream  inputStream = conn.getInputStream();
						bos = new ByteArrayOutputStream();
						int len = 0;
						byte[] buffer = new byte[1024];
						while((len = inputStream.read(buffer ))!=-1){
							bos.write(buffer, 0, len);
						}
						bos.flush();
						inputStream.close();
						bos.close();

//					/*此部分为UI界面显示*/
////						runOnUiThread(new Runnable() {
////							public void run() {
////								try {
////									//mTextView.setText(new String(bos.toByteArray(),"utf-8"));
////								} catch (UnsupportedEncodingException e) {
////									e.printStackTrace();
////								}
////							}
////						});
					}
					Log.i("aaaaaaaa","bbbbbbbb");
				} catch (Exception e) {
					e.printStackTrace();
					//e.printStackTrace();
					Log.i("aaaaaaaa", "eeeeeee");
				}
//				/*第二种方式*/
//				try {
//					String data = "content="+ URLEncoder.encode(content)+"&date="+simpleDateFormat.format(getdate())+"&userid="+userid;
//					//String urlPath = "http://192.168.1.9:80/JJKSms/RecSms.php";
//					URL url = new URL(path);
//
//					HttpURLConnection httpURLConnection = (HttpURLConnection)url.openConnection();
//					httpURLConnection.setConnectTimeout(3000);     //设置连接超时时间
//					httpURLConnection.setDoInput(true);                  //打开输入流，以便从服务器获取数据
//					httpURLConnection.setDoOutput(true);                 //打开输出流，以便向服务器提交数据
//					httpURLConnection.setRequestMethod("POST");     //设置以Post方式提交数据
//					httpURLConnection.setUseCaches(false);               //使用Post方式不能使用缓存
//					//设置请求体的类型是文本类型
//					httpURLConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
//					//设置请求体的长度
//					httpURLConnection.setRequestProperty("Content-Length", data.length()+"");
//					//获得输出流，向服务器写入数据
//					OutputStream outputStream = httpURLConnection.getOutputStream();
//					outputStream.write(data.getBytes());
//
//					int response = httpURLConnection.getResponseCode();            //获得服务器的响应码
//					if(response == HttpURLConnection.HTTP_OK) {
//						InputStream inptStream = httpURLConnection.getInputStream();
//						//return ;                     //处理服务器的响应结果
//					}
//				} catch (IOException e) {
//					//e.printStackTrace();
//					//return ;
//				}


				/*第一种方式*/
//				try {
//					URL url = new URL(path);
//					HttpURLConnection conn = (HttpURLConnection) url.openConnection();
//					conn.setRequestMethod("POST");
//					conn.setReadTimeout(5000);
//					conn.setConnectTimeout(5000);
//
//
//					//拼接处要提交的字符串
//					@SuppressWarnings("deprecation")
//					String data = "content="+ URLEncoder.encode(content)+"&date="+URLEncoder.encode(simpleDateFormat.format(getdate())) + "&userid=" + URLEncoder.encode(userid);
//
//					//为post添加两行属性
//					conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
//					conn.setRequestProperty("Content-Length", data.length()+"");
//
//					//因为post是通过流往服务器提交数据的，所以我们需要设置一个输出流
//					//设置打开输出流
//					conn.setDoOutput(true);
//					//拿到输出流
//					OutputStream os = conn.getOutputStream();
//					//使用输出流向服务器提交数据
//					os.write(data.getBytes());
//
////					if(conn.getResponseCode() == 200){
////						InputStream is = conn.getInputStream();
////						String text = utils.getTextFromStream(is);
////						//消息队列机制，把读取出来的数据交给handler发送给主线程刷新UI
////						Message msg = handler.obtainMessage();
////						msg.obj = text;
////						handler.sendMessage(msg);
////					}
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
			}

		};
		t.start();
	}
//post方法截止

	private void initCamera(SurfaceHolder surfaceHolder) {
		if (surfaceHolder == null) {
			throw new IllegalStateException("No SurfaceHolder provided");
		}
		if (cameraManager.isOpen()) {
			Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
			return;
		}
		try {
			cameraManager.openDriver(surfaceHolder);
			// Creating the handler starts the preview, which can also throw a
			// RuntimeException.
			if (handler == null) {
				handler = new CaptureActivityHandler(this, cameraManager, DecodeThread.ALL_MODE);
			}

			initCrop();
		} catch (IOException ioe) {
			Log.w(TAG, ioe);
			displayFrameworkBugMessageAndExit();
		} catch (RuntimeException e) {
			// Barcode Scanner has seen crashes in the wild of this variety:
			// java.?lang.?RuntimeException: Fail to connect to camera service
			Log.w(TAG, "Unexpected error initializing camera", e);
			displayFrameworkBugMessageAndExit();
		}
	}

	private void displayFrameworkBugMessageAndExit() {
		// camera error
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(getString(R.string.app_name));
		builder.setMessage("相机打开出错，请稍后重试");
		builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {

			@Override
			public void onClick(DialogInterface dialog, int which) {
				finish();
			}

		});
		builder.setOnCancelListener(new DialogInterface.OnCancelListener() {

			@Override
			public void onCancel(DialogInterface dialog) {
				finish();
			}
		});
		builder.show();
	}

	public void restartPreviewAfterDelay(long delayMS) {
		if (handler != null) {
			handler.sendEmptyMessageDelayed(R.id.restart_preview, delayMS);
		}
	}

	public Rect getCropRect() {
		return mCropRect;
	}

	/**
	 * 初始化截取的矩形区域
	 */
	private void initCrop() {
		int cameraWidth = cameraManager.getCameraResolution().y;
		int cameraHeight = cameraManager.getCameraResolution().x;

		/** 获取布局中扫描框的位置信息 */
		int[] location = new int[2];
		scanCropView.getLocationInWindow(location);

		int cropLeft = location[0];
		int cropTop = location[1] - getStatusBarHeight();

		int cropWidth = scanCropView.getWidth();
		int cropHeight = scanCropView.getHeight();

		/** 获取布局容器的宽高 */
		int containerWidth = scanContainer.getWidth();
		int containerHeight = scanContainer.getHeight();

		/** 计算最终截取的矩形的左上角顶点x坐标 */
		int x = cropLeft * cameraWidth / containerWidth;
		/** 计算最终截取的矩形的左上角顶点y坐标 */
		int y = cropTop * cameraHeight / containerHeight;

		/** 计算最终截取的矩形的宽度 */
		int width = cropWidth * cameraWidth / containerWidth;
		/** 计算最终截取的矩形的高度 */
		int height = cropHeight * cameraHeight / containerHeight;

		/** 生成最终的截取的矩形 */
		mCropRect = new Rect(x, y, width + x, height + y);
	}

	private int getStatusBarHeight() {
		try {
			Class<?> c = Class.forName("com.android.internal.R$dimen");
			Object obj = c.newInstance();
			Field field = c.getField("status_bar_height");
			int x = Integer.parseInt(field.get(obj).toString());
			return getResources().getDimensionPixelSize(x);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 0;
	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {

		if (resultCode == RESULT_OK) {
			final ProgressDialog progressDialog;
			switch (requestCode) {
				case REQUEST_CODE:

					// 获取选中图片的路径
					Cursor cursor = getContentResolver().query(
							intent.getData(), null, null, null, null);
					if (cursor.moveToFirst()) {
						photoPath = cursor.getString(cursor
								.getColumnIndex(MediaStore.Images.Media.DATA));
					}
					cursor.close();

					progressDialog = new ProgressDialog(this);
					progressDialog.setMessage("正在扫描...");
					progressDialog.setCancelable(false);
					progressDialog.show();

					new Thread(new Runnable() {

						@Override
						public void run() {

							Bitmap img = BitmapUtils
									.getCompressedBitmap(photoPath);

							BitmapDecoder decoder = new BitmapDecoder(
									CaptureActivity.this);
							Result result = decoder.getRawResult(img);

							if (result != null) {
								Message m = mHandler.obtainMessage();
								m.what = PARSE_BARCODE_SUC;
								m.obj = ResultParser.parseResult(result)
										.toString();
								mHandler.sendMessage(m);
								/*测试显示*/
							/*测试二维码的内容。*/
								Log.i("CaptureActivity", "value:" + m.obj);

							/*测试二维码读取时间。*/
								//Date time1;
								simpleDateFormat.format(getdate());
								Log.i("CaptureActivity", "value:" + simpleDateFormat.format(getdate()));

							/*测试读取MAC地址。*/
								Log.i("CaptureActivity", "value:" + strMacadds);
								toPost(String.valueOf(m.obj), strMacadds);

							} else {
								Message m = mHandler.obtainMessage();
								m.what = PARSE_BARCODE_FAIL;
								mHandler.sendMessage(m);
							}
							progressDialog.dismiss();

						}
					}).start();
					break;
			}
		}
	}

	@Override
	public void onClick(View v) {
		final int id = v.getId();
		switch (id) {
		case R.id.capture_scan_photo: // 图片识别
			// 打开手机中的相册
			Intent innerIntent = new Intent(Intent.ACTION_GET_CONTENT); // "android.intent.action.GET_CONTENT"
			innerIntent.setType("image/*");
			Intent wrapperIntent = Intent.createChooser(innerIntent,
					"选择二维码图片");
			this.startActivityForResult(wrapperIntent, REQUEST_CODE);


			break;

		case R.id.capture_flashlight:
			if (isFlashlightOpen) {
				cameraManager.setTorch(false); // 关闭闪光灯
				isFlashlightOpen = false;
			}
			else {
				cameraManager.setTorch(true); // 打开闪光灯
				isFlashlightOpen = true;
			}
			//

			break;
		default:
			break;
		}
	}


}