package com.gilmar.apuntalo

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.widget.RemoteViews
import android.content.*
import android.os.Build
import android.os.Bundle
import android.location.LocationManager
import android.location.Location
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.ui.viewinterop.AndroidView
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.CalendarContract
import android.database.Cursor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private var appContext:Context?=null
private val bg=Color(0xFF17191F)
private val card=Color(0xFF252932)
private val teal=Color(0xFF65B9A5)
private const val CHANNEL="apuntalo_tasks"
private fun monthKey(t:Long=System.currentTimeMillis())=SimpleDateFormat("yyyy-MM",Locale.US).format(Date(t))
private fun fmt(t:Long)=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(Date(t))
private fun parse(s:String):Long?=try { SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).apply{isLenient=false}.parse(s)?.time }catch(_:Exception){null}
private fun moneyValue(raw:String):java.math.BigDecimal?{
 val cleaned=raw.trim().replace(Regex("[\\s\\$€£]"),"")
 if(cleaned.isEmpty())return null
 val comma=cleaned.lastIndexOf(',')
 val dot=cleaned.lastIndexOf('.')
 val normalized=when{
  comma>=0&&dot>=0->if(comma>dot)cleaned.replace(".","").replace(',','.') else cleaned.replace(",","")
  comma>=0->if(cleaned.length-comma-1==3&&cleaned.substring(0,comma).length<=3)cleaned.replace(",","") else cleaned.replace(',','.')
  dot>=0&&cleaned.length-dot-1==3&&cleaned.substring(0,dot).length<=3->cleaned.replace(".","")
  else->cleaned
 }
 return runCatching{java.math.BigDecimal(normalized).setScale(2,java.math.RoundingMode.HALF_UP)}.getOrNull()
}
private fun expenseTotal(expenses:JSONArray):java.math.BigDecimal{
 var sum=java.math.BigDecimal.ZERO
 for(i in 0 until expenses.length()){
  val value=expenses.getJSONObject(i).optString("amount","0")
  sum=sum.add(moneyValue(value)?:java.math.BigDecimal.ZERO)
 }
 return sum
}
private fun read(p:android.content.SharedPreferences,k:String):JSONArray=try{JSONArray(p.getString(k,"[]"))}catch(_:Exception){JSONArray()}
private fun save(p:android.content.SharedPreferences,k:String,a:JSONArray){p.edit().putString(k,a.toString()).apply();if(k=="tasks")try{p.javaClass;val context=appContext;context?.sendBroadcast(Intent("com.gilmar.apuntalo.REFRESH_WIDGET").setPackage(context.packageName))}catch(_:Exception){}}
private fun remove(a:JSONArray,id:Long):JSONArray=JSONArray().also{out->for(i in 0 until a.length()){val o=a.getJSONObject(i);if(o.optLong("id")!=id)out.put(o)}}
private fun notifyAlarm(ctx:Context,id:Long,title:String,time:Long){
    if(!ctx.getSharedPreferences("apuntalo_alpha",0).getBoolean("notifications_enabled",true))return
    val intent=Intent(ctx,ReminderReceiver::class.java).putExtra("title",title).putExtra("id",id)
    val pi=PendingIntent.getBroadcast(ctx,(id%Int.MAX_VALUE).toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val am=ctx.getSystemService(AlarmManager::class.java)
    if(Build.VERSION.SDK_INT>=31 && am.canScheduleExactAlarms())am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pi)
    else if(Build.VERSION.SDK_INT<31)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pi)
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pi)
}

private fun calendarAllowed(ctx:Context):Boolean=
 ContextCompat.checkSelfPermission(ctx,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED&&
 ContextCompat.checkSelfPermission(ctx,Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED

private fun syncDeviceCalendar(ctx:Context,preferLocal:Boolean=false):Int{
 if(!calendarAllowed(ctx))return 0
 val prefs=ctx.getSharedPreferences("apuntalo_alpha",0)
 val resolver=ctx.contentResolver
 val calendars=mutableListOf<Long>()
 resolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf(CalendarContract.Calendars._ID),
  "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
  arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),null)?.use{cursor->
  while(cursor.moveToNext())calendars.add(cursor.getLong(0))
 }
 if(calendars.isEmpty())return 0
 val calendarId=calendars.first()
 val tasks=read(prefs,"tasks")
 val byId=mutableMapOf<Long,JSONObject>()
 for(i in 0 until tasks.length()){val task=tasks.getJSONObject(i);byId[task.optLong("id")]=task}
 val prefix="APUNTALO_TASK_ID:"
 var changed=false
 var synced=0
 val seen=mutableSetOf<Long>()
 resolver.query(CalendarContract.Events.CONTENT_URI,
  arrayOf(CalendarContract.Events._ID,CalendarContract.Events.DESCRIPTION,CalendarContract.Events.TITLE,CalendarContract.Events.DTSTART),
  "${CalendarContract.Events.DESCRIPTION} LIKE ?",arrayOf("$prefix%"),null)?.use{cursor->
  while(cursor.moveToNext()){
   val eventId=cursor.getLong(0)
   val description=cursor.getString(1)?:""
   val id=description.substringAfter(prefix).substringBefore("\\n").trim().toLongOrNull()?:continue
   seen.add(id)
   val task=byId[id]
   if(task==null||task.optLong("time")<=0L){
    resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId),null,null)
    synced++
   }else{
    val eventTitle=cursor.getString(2)?:""
    val eventTime=cursor.getLong(3)
    if(eventTitle!=task.optString("title")||eventTime!=task.optLong("time")){
     if(preferLocal){
      resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId),ContentValues().apply{
       put(CalendarContract.Events.TITLE,task.optString("title"))
       put(CalendarContract.Events.DTSTART,task.optLong("time"))
       put(CalendarContract.Events.DTEND,task.optLong("time")+30*60*1000L)
      },null,null)
     }else{
      task.put("title",eventTitle).put("time",eventTime)
      changed=true
     }
     synced++
    }
   }
  }
 }
 for(i in 0 until tasks.length()){
  val task=tasks.getJSONObject(i)
  val id=task.optLong("id")
  val time=task.optLong("time")
  if(time<=0L||id in seen)continue
  val values=ContentValues().apply{
   put(CalendarContract.Events.CALENDAR_ID,calendarId)
   put(CalendarContract.Events.TITLE,task.optString("title"))
   put(CalendarContract.Events.DESCRIPTION,prefix+id)
   put(CalendarContract.Events.DTSTART,time)
   put(CalendarContract.Events.DTEND,time+30*60*1000L)
   put(CalendarContract.Events.EVENT_TIMEZONE,TimeZone.getDefault().id)
  }
  resolver.insert(CalendarContract.Events.CONTENT_URI,values)
  synced++
 }
 if(changed)save(prefs,"tasks",tasks)
 return synced
}
private fun resetDailyRecurringTasks(p:android.content.SharedPreferences){
 val today=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
 val tasks=read(p,"tasks")
 var changed=false
 for(i in 0 until tasks.length()){
  val item=tasks.getJSONObject(i)
  if(!item.optBoolean("recurring",false))continue
  val last=item.optString("lastDailyReset","")
  if(last!=today){
   // Preserve the first day's completion state; reset only after the day changes.
   if(last.isNotBlank())item.put("done",false)
   item.put("lastDailyReset",today)
   changed=true
  }
 }
 if(changed)save(p,"tasks",tasks)
}
private fun carryRecurringExpenses(p:android.content.SharedPreferences,currentMonth:String){
 val source=read(p,"expenses")
 val existing=mutableSetOf<String>()
 val latest=mutableMapOf<Long,JSONObject>()
 for(i in 0 until source.length()){
  val item=source.getJSONObject(i)
  val origin=item.optLong("recurringOrigin",item.optLong("id"))
  val month=item.optString("month","")
  existing.add("$origin:$month")
  if(month<=currentMonth && (latest[origin]==null || month>latest[origin]!!.optString("month","")))
   latest[origin]=item
 }
 var changed=false
 for((origin,item) in latest){
  if(!item.optBoolean("recurring",false))continue
  val start=item.optString("month","")
  if(start.isBlank()||start>=currentMonth)continue
  val date=runCatching{SimpleDateFormat("yyyy-MM",Locale.US).apply{isLenient=false}.parse(start)}.getOrNull()?:continue
  val cursor=Calendar.getInstance().apply{time=date;set(Calendar.DAY_OF_MONTH,1)}
  val end=Calendar.getInstance().apply{time=SimpleDateFormat("yyyy-MM",Locale.US).parse(currentMonth)!!;set(Calendar.DAY_OF_MONTH,1)}
  var count=0
  while(cursor.before(end)&&count++<240){
   cursor.add(Calendar.MONTH,1)
   val key=SimpleDateFormat("yyyy-MM",Locale.US).format(cursor.time)
   if("$origin:$key" !in existing){
    val copy=JSONObject(item.toString())
    copy.put("id",System.currentTimeMillis()+source.length()+count+origin%10000)
    copy.put("recurringOrigin",origin)
    copy.put("month",key)
    source.put(copy)
    existing.add("$origin:$key")
    changed=true
   }
  }
 }
 if(changed)save(p,"expenses",source)
}
private fun shareTask(ctx:Context,title:String,time:Long){
 val message="📌 APÚNTALO: "+title+(if(time>0L)"\\n📅 "+fmt(time) else "")
 val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,message)}
 ctx.startActivity(Intent.createChooser(intent,"Compartir tarea"))
}
private fun shareList(ctx:Context,heading:String,items:JSONArray){
 val content=(0 until items.length()).joinToString("\n"){i->
  val item=items.getJSONObject(i)
  (if(item.optBoolean("done"))"☑ " else "☐ ")+item.optString("title")+
  (if(item.optLong("time")>0)" · "+fmt(item.optLong("time")) else "")
 }
 val message="📌 APÚNTALO · "+heading+"\n"+(if(content.isBlank())"—" else content)
 val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,message)}
 ctx.startActivity(Intent.createChooser(intent,heading))
}
private fun cancelAlarm(ctx:Context,id:Long){
    val pi=PendingIntent.getBroadcast(ctx,(id%Int.MAX_VALUE).toInt(),Intent(ctx,ReminderReceiver::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    if(pi!=null)ctx.getSystemService(AlarmManager::class.java).cancel(pi)
}
class ReminderReceiver:android.content.BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent){
    if(!c.getSharedPreferences("apuntalo_alpha",0).getBoolean("notifications_enabled",true))return
    val nm=c.getSystemService(NotificationManager::class.java)
    val n=android.app.Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("APÚNTALO").setContentText(i.getStringExtra("title")?:"Tienes una tarea pendiente").setAutoCancel(true).build()
    nm.notify((i.getLongExtra("id",0)%Int.MAX_VALUE).toInt(),n)
 }
}
class BootReceiver:android.content.BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent){
  val a=read(c.getSharedPreferences("apuntalo_alpha",0),"tasks")
  for(j in 0 until a.length()){val o=a.getJSONObject(j);val t=o.optLong("time");if(!o.optBoolean("done")&&t>System.currentTimeMillis())notifyAlarm(c,o.optLong("id"),o.optString("title"),t)}
 }
}
class MainActivity:ComponentActivity(){
 val backupRevision=mutableIntStateOf(0)
 private val backupExport=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
  if(uri!=null)runCatching{
   val prefs=getSharedPreferences("apuntalo_alpha",0)
   val data=JSONObject().put("format","APUNTALO_BACKUP").put("version",1)
   val values=JSONObject()
   for((key,value) in prefs.all){
    when(value){
     is String->values.put(key,JSONObject().put("type","string").put("value",value))
     is Boolean->values.put(key,JSONObject().put("type","boolean").put("value",value))
     is Int->values.put(key,JSONObject().put("type","int").put("value",value))
     is Long->values.put(key,JSONObject().put("type","long").put("value",value))
     is Float->values.put(key,JSONObject().put("type","float").put("value",value.toDouble()))
    }
   }
   data.put("preferences",values)
   val wallpaper=File(filesDir,"wallpaper.jpg")
   if(wallpaper.exists())data.put("wallpaper",Base64.encodeToString(wallpaper.readBytes(),Base64.NO_WRAP))
   contentResolver.openOutputStream(uri)?.use{it.write(data.toString().toByteArray(Charsets.UTF_8))}
    ?:error("No se pudo abrir el archivo")
   Toast.makeText(this,"Respaldo guardado. Puedes elegir Google Drive.",Toast.LENGTH_LONG).show()
  }.onFailure{Toast.makeText(this,"Error al guardar respaldo: ${it.message}",Toast.LENGTH_LONG).show()}
 }
 private val backupImport=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri!=null)runCatching{
   val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Archivo inaccesible")
   require(bytes.size<30_000_000){"Respaldo demasiado grande"}
   val data=JSONObject(String(bytes,Charsets.UTF_8))
   require(data.optString("format")=="APUNTALO_BACKUP" && data.optInt("version")==1){"No es un respaldo compatible"}
   val values=data.getJSONObject("preferences")
   val prefs=getSharedPreferences("apuntalo_alpha",0)
   val editor=prefs.edit().clear()
   val keys=values.keys()
   while(keys.hasNext()){
    val key=keys.next()
    val entry=values.getJSONObject(key)
    when(entry.getString("type")){
     "string"->editor.putString(key,entry.getString("value"))
     "boolean"->editor.putBoolean(key,entry.getBoolean("value"))
     "int"->editor.putInt(key,entry.getInt("value"))
     "long"->editor.putLong(key,entry.getLong("value"))
     "float"->editor.putFloat(key,entry.getDouble("value").toFloat())
    }
   }
   check(editor.commit()){"No se pudieron restaurar los datos"}
   val wallpaper=File(filesDir,"wallpaper.jpg")
   if(data.has("wallpaper"))wallpaper.writeBytes(Base64.decode(data.getString("wallpaper"),Base64.DEFAULT))
   else wallpaper.delete()
   wallpaperRevision.intValue++
   backupRevision.intValue++
   if(calendarAllowed(this))runCatching{syncDeviceCalendar(this,true)}
   Toast.makeText(this,"Respaldo restaurado",Toast.LENGTH_LONG).show()
  }.onFailure{Toast.makeText(this,"Error al restaurar: ${it.message}",Toast.LENGTH_LONG).show()}
 }
 fun createBackup(){backupExport.launch("APUNTALO-respaldo-"+SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())+".json")}
 fun restoreBackup(){backupImport.launch(arrayOf("application/json","application/octet-stream"))}
 override fun onResume(){
  super.onResume()
  if(calendarAllowed(this))runCatching{syncDeviceCalendar(this)}.onSuccess{calendarRevision.intValue++}
 }

 val calendarRevision=mutableIntStateOf(0)
 private val calendarPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permissions->
  if(permissions.values.all{it})runCatching{syncDeviceCalendar(this)}.onSuccess{calendarRevision.intValue++}
 }
 fun synchronizeCalendar(){
  if(calendarAllowed(this))runCatching{syncDeviceCalendar(this)}.onSuccess{calendarRevision.intValue++}
  else calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR))
 }
 val carRevision=mutableIntStateOf(0)
 private val carPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permissions->
  if(permissions.values.any{it})saveCarLocation()
 }
 fun rememberCar(){
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
     ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)saveCarLocation()
  else carPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
 }
 private fun saveCarLocation(){
  val lm=getSystemService(LocationManager::class.java)
  val provider=when{
   lm.isProviderEnabled(LocationManager.GPS_PROVIDER)->LocationManager.GPS_PROVIDER
   lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)->LocationManager.NETWORK_PROVIDER
   else->return
  }
  try{
   if(Build.VERSION.SDK_INT>=30)lm.getCurrentLocation(provider,null,mainExecutor){location->location?.let{storeCar(it)}}
   else lm.getLastKnownLocation(provider)?.let{storeCar(it)}
  }catch(_:SecurityException){}
 }
 private fun storeCar(location:Location){
  getSharedPreferences("apuntalo_alpha",0).edit().putString("car_lat",location.latitude.toString()).putString("car_lon",location.longitude.toString()).putLong("car_saved",System.currentTimeMillis()).apply()
  carRevision.intValue++
 }
 fun clearCar(){
  getSharedPreferences("apuntalo_alpha",0).edit().remove("car_lat").remove("car_lon").remove("car_saved").apply()
  carRevision.intValue++
 }

 private val wallpaperPicker=registerForActivityResult(ActivityResultContracts.GetContent()){uri->
  if(uri!=null)try{
   val orientation=contentResolver.openInputStream(uri)?.use{stream->
    ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
   }?:ExifInterface.ORIENTATION_NORMAL
   val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
   contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
   var sample=1
   while(bounds.outWidth/sample>2200||bounds.outHeight/sample>2200)sample*=2
   val source=contentResolver.openInputStream(uri)?.use{
    BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})
   }?:throw IllegalArgumentException("Imagen no válida")
   val matrix=Matrix().apply{
    when(orientation){
     ExifInterface.ORIENTATION_ROTATE_90->postRotate(90f)
     ExifInterface.ORIENTATION_ROTATE_180->postRotate(180f)
     ExifInterface.ORIENTATION_ROTATE_270->postRotate(270f)
     ExifInterface.ORIENTATION_FLIP_HORIZONTAL->postScale(-1f,1f)
     ExifInterface.ORIENTATION_FLIP_VERTICAL->postScale(1f,-1f)
     ExifInterface.ORIENTATION_TRANSPOSE->{postRotate(90f);postScale(-1f,1f)}
     ExifInterface.ORIENTATION_TRANSVERSE->{postRotate(270f);postScale(-1f,1f)}
    }
   }
   val upright=Bitmap.createBitmap(source,0,0,source.width,source.height,matrix,true)
   val targetRatio=9f/19.5f
   val cropWidth=if(upright.width.toFloat()/upright.height>targetRatio)(upright.height*targetRatio).toInt() else upright.width
   val cropHeight=if(upright.width.toFloat()/upright.height>targetRatio)upright.height else (upright.width/targetRatio).toInt()
   val portrait=Bitmap.createBitmap(upright,(upright.width-cropWidth)/2,(upright.height-cropHeight)/2,cropWidth,cropHeight)
   File(filesDir,"wallpaper.jpg").outputStream().use{portrait.compress(Bitmap.CompressFormat.JPEG,92,it)}
   if(portrait!==upright)portrait.recycle()
   if(upright!==source)upright.recycle()
   source.recycle()
   getSharedPreferences("apuntalo_alpha",0).edit().putLong("wallpaper_revision",System.currentTimeMillis()).apply()
   wallpaperRevision.value++
  }catch(_:Exception){}
 }
 val wallpaperRevision=mutableIntStateOf(0)
 fun chooseWallpaper(){wallpaperPicker.launch("image/*")}
 fun removeWallpaper(){File(filesDir,"wallpaper.jpg").delete();wallpaperRevision.value++}

 override fun onCreate(savedInstanceState:Bundle?){
 super.onCreate(savedInstanceState)
 appContext=applicationContext
 getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Recordatorios de tareas",NotificationManager.IMPORTANCE_HIGH))
 setContent {
 val density=LocalDensity.current
 CompositionLocalProvider(LocalDensity provides Density(density.density,density.fontScale*1.12f)) { AppEntry(this) }
 }
 }
}
@Composable fun AppEntry(activity:MainActivity){
 var showingSplash by remember{mutableStateOf(true)}
 LaunchedEffect(Unit){delay(2000L);showingSplash=false}
 if(showingSplash){
 BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)){
 Image(painter=painterResource(id=R.drawable.apuntalo_splash),contentDescription="APÚNTALO",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
 val imageWidth=minOf(maxWidth,maxHeight*(2f/3f))
 val imageHeight=imageWidth*1.5f
 val left=(maxWidth-imageWidth)/2
 val top=(maxHeight-imageHeight)/2
 val centerX=left+imageWidth*0.5f
 val centerY=top+imageHeight*(809f/1080f)
 val coverSize=imageWidth*0.18f
 Box(Modifier.offset(x=centerX-coverSize/2,y=centerY-coverSize/2).size(coverSize).background(Color(0xFFFEFEFD),CircleShape))
 val spinnerSize=imageWidth*0.125f
 CircularProgressIndicator(modifier=Modifier.offset(x=centerX-spinnerSize/2,y=centerY-spinnerSize/2).size(spinnerSize),color=Color(0xFF12B9AA),trackColor=Color(0xFFDADADA),strokeWidth=4.dp)
 }
 }else Screen(activity)
}
@Composable fun ThemeThumbnail(name:String,selected:Boolean,onChoose:()->Unit){
 val tone=when(name){
 "Crimson"->Color(0xFFFF6079);"Ocean"->Color(0xFF59B8EA);"Emerald"->Color(0xFF55D6A1)
 "Purple Haze"->Color(0xFFBB8CF5);"Burgundy"->Color(0xFFE287A0);"Sunset"->Color(0xFFFFA16D)
 "Cyberpunk"->Color(0xFF00E5D0);"Dracula"->Color(0xFFBD93F9);"Military"->Color(0xFFC0B887)
 "Forest"->Color(0xFF88C88A);"Graphite"->Color(0xFFAAB5C7);"Arctic"->Color(0xFF287FA5)
 "OLED Black"->Color(0xFFB0B0B0);"Pure White"->Color(0xFF60B9AD)
 else->teal
 }
 val base=when(name){
 "Crimson"->Color(0xFF260B17);"OLED Black"->Color.Black;"Ocean"->Color(0xFF091D32)
 "Emerald"->Color(0xFF09251F);"Purple Haze"->Color(0xFF1C102E)
 "Burgundy"->Color(0xFF30131F);"Graphite"->Color(0xFF1B2029)
 "Arctic"->Color(0xFFE6F0F5);"Pure White"->Color(0xFFF7F9FC)
 "Forest"->Color(0xFF10271C);"Sunset"->Color(0xFF35202A)
 "Cyberpunk"->Color(0xFF100D28);"Dracula"->Color(0xFF282A36)
 "Military"->Color(0xFF24291F);else->bg
 }
 val light=name=="Pure White"||name=="Arctic"
 Column(Modifier.fillMaxWidth().padding(3.dp).border(if(selected)2.dp else 1.dp,if(selected)tone else tone.copy(alpha=.25f),RoundedCornerShape(12.dp)).clickable(onClick=onChoose).padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally){
 Box(Modifier.fillMaxWidth().height(94.dp).clip(RoundedCornerShape(8.dp))){
 Canvas(Modifier.fillMaxSize()){
 val w=size.width;val h=size.height
 drawRect(brush=Brush.verticalGradient(listOf(base,base,tone.copy(alpha=.38f))))
 val wave=Path().apply{moveTo(0f,h*.78f);cubicTo(w*.30f,h*.96f,w*.54f,h*.96f,w*.72f,h*.62f);cubicTo(w*.85f,h*.46f,w*.94f,h*.48f,w,h*.42f);lineTo(w,h);lineTo(0f,h);close()}
 drawPath(wave,brush=Brush.verticalGradient(listOf(tone.copy(alpha=.16f),tone.copy(alpha=.80f)),startY=h*.42f,endY=h))
 val edge=Path().apply{moveTo(0f,h*.78f);cubicTo(w*.30f,h*.96f,w*.54f,h*.96f,w*.72f,h*.62f);cubicTo(w*.85f,h*.46f,w*.94f,h*.48f,w,h*.42f)}
 drawPath(edge,tone,style=Stroke(width=1.5.dp.toPx()))
 }
 Column(Modifier.padding(6.dp)){
 Text("APÚNTALO",color=if(light)Color.Black else Color.White,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=1)
 Spacer(Modifier.height(5.dp))
 Box(Modifier.fillMaxWidth(.70f).height(5.dp).background(tone.copy(alpha=.75f),RoundedCornerShape(5.dp)))
 }
 }
 Text((if(selected)"✓ " else "")+name,color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.labelSmall,maxLines=1)
 }
}
private val translations=mapOf(
 "que se te olvida" to listOf("so you don't forget","così non te ne dimentichi","pour ne pas oublier"),
 "Creada por Gilmar" to listOf("Created by Gilmar","Creata da Gilmar","Créée par Gilmar"),
 "☑ Tareas" to listOf("☑ Tasks","☑ Attività","☑ Tâches"),
 "🛒 Compras" to listOf("🛒 Shopping","🛒 Acquisti","🛒 Achats"),
 "▦ Calendario" to listOf("▦ Calendar","▦ Calendario","▦ Calendario"),
 "◈ Gastos" to listOf("◈ Expenses","◈ Spese","◈ Dépenses"),
 "⚙ Config" to listOf("⚙ Settings","⚙ Impostazioni","⚙ Paramètres"),
 "Listas de compras" to listOf("Shopping lists","Liste della spesa","Listes de courses"),
 "Añadir producto" to listOf("Add item","Aggiungi prodotto","Ajouter un article"),
 "Borrar" to listOf("Delete","Elimina","Supprimer"),
 "Día \$selectedDay · Toca un día para consultar sus tareas" to listOf("Day \$selectedDay · Tap a day to see tasks","Giorno \$selectedDay · Tocca un giorno per vedere le attività","Jour \$selectedDay · Touchez un jour pour voir les tâches"),
 "TU PRODUCTIVIDAD" to listOf("YOUR PRODUCTIVITY","LA TUA PRODUTTIVITÀ","VOTRE PRODUCTIVITÉ"),
 "Mis tareas" to listOf("My tasks","Le mie attività","Mes tâches"),
 "Próximos recordatorios" to listOf("Upcoming reminders","Promemoria imminenti","Rappels à venir"),
 "+ Tarea" to listOf("+ Task","+ Attività","+ Tâche"),
 "Compartir" to listOf("Share","Condividi","Partager"),
 "Resumen financiero · " to listOf("Financial summary · ","Riepilogo finanziario · ","Résumé financier · "),
 "Ingreso mensual (ej. 2500.00)" to listOf("Monthly income (e.g. 2500.00)","Entrate mensili (es. 2500.00)","Revenu mensuel (ex. 2500.00)"),
 "BALANCE DISPONIBLE" to listOf("AVAILABLE BALANCE","SALDO DISPONIBILE","SOLDE DISPONIBLE"),
 "DISTRIBUCIÓN DE GASTOS" to listOf("EXPENSE BREAKDOWN","RIPARTIZIONE SPESE","RÉPARTITION DES DÉPENSES"),
 "Gastado" to listOf("Spent","Speso","Dépensé"),
 "Registra un gasto para ver tu gráfica." to listOf("Add an expense to see your chart.","Aggiungi una spesa per vedere il grafico.","Ajoutez une dépense pour voir le graphique."),
 "PRESUPUESTOS" to listOf("BUDGETS","BUDGET","BUDGETS"),
 "Listo" to listOf("Done","Fatto","Terminé"),
 "Editar límites" to listOf("Edit limits","Modifica limiti","Modifier les limites"),
 "Presupuesto excedido" to listOf("Budget exceeded","Budget superato","Budget dépassé"),
 "Te acercas al límite" to listOf("Approaching limit","Vicino al limite","Limite bientôt atteint"),
 "+ Gasto" to listOf("+ Expense","+ Spesa","+ Dépense"),
 "Notificaciones" to listOf("Notifications","Notifiche","Notifications"),
 "Recordatorios de tareas con fecha y hora" to listOf("Timed task reminders","Promemoria delle attività con data e ora","Rappels des tâches avec date et heure"),
 "Wallpaper personalizado" to listOf("Custom wallpaper","Sfondo personalizzato","Fond d’écran personnalisé"),
 "Usa una foto de tu galería como fondo de la aplicación" to listOf("Use a gallery photo as the app background","Usa una foto della galleria come sfondo dell'app","Utilisez une photo de votre galerie comme fond de l’application"),
 "⬆ Subir wallpaper" to listOf("⬆ Upload wallpaper","⬆ Carica sfondo","⬆ Importer un fond"),
 "Quitar fondo" to listOf("Remove wallpaper","Rimuovi sfondo","Supprimer le fond"),
 "Tema visual" to listOf("Visual theme","Tema visivo","Thème visuel"),
 "Tema aleatorio" to listOf("Random theme","Tema casuale","Thème aléatoire"),
 "Un tema diferente al abrir la app" to listOf("A different theme each time you open the app","Un tema diverso a ogni apertura dell'app","Un thème différent à chaque ouverture"),
 "Gasto" to listOf("Expense","Spesa","Dépense"),
 "Tarea" to listOf("Task","Attività","Tâche"),
 "Descripción" to listOf("Description","Descrizione","Description"),
 "Cantidad" to listOf("Amount","Importo","Montant"),
 "Categoría" to listOf("Category","Categoria","Catégorie"),
 "Fecha y hora (dd/MM/yyyy HH:mm)" to listOf("Date and time (dd/MM/yyyy HH:mm)","Data e ora (dd/MM/yyyy HH:mm)","Date et heure (dd/MM/yyyy HH:mm)"),
 "Apariencia" to listOf("Appearance","Aspetto","Apparence"),
 "Ingreso mensual" to listOf("Monthly income","Reddito mensile","Revenu mensuel"),
 "Ejemplo: $2,000.00" to listOf("Example: $2,000.00","Esempio: $2.000,00","Exemple : 2 000,00 $"),
 "Recordarme con notificación" to listOf("Remind me with a notification","Ricordamelo con una notifica","Me rappeler par notification"),
 "Elegir fecha" to listOf("Choose date","Scegli la data","Choisir la date"),
 "Elegir hora" to listOf("Choose time","Scegli l’ora","Choisir l’heure"),
 "Deja vacío para una tarea sin alarma" to listOf("Leave blank for a task without an alarm","Lascia vuoto per un'attività senza allarme","Laissez vide pour une tâche sans alarme"),
 "Guardar" to listOf("Save","Salva","Enregistrer"),
 "Cancelar" to listOf("Cancel","Annulla","Annuler"),
 "Hogar" to listOf("Home","Casa","Maison"),
 "Comida" to listOf("Food","Cibo","Alimentation"),
 "Transporte" to listOf("Transport","Trasporti","Transport"),
 "Familia" to listOf("Family","Famiglia","Famille"),
 "Salud" to listOf("Health","Salute","Santé"),
 "Otros" to listOf("Other","Altro","Autres"),
 "Idioma" to listOf("Language","Lingua","Langue"),
 "Español" to listOf("Spanish","Spagnolo","Espagnol"),
 "Inglés" to listOf("English","Inglese","Anglais"),
 "Italiano" to listOf("Italian","Italiano","Italien"),
 "Francés" to listOf("French","Francese","Français"),
 "🚗 Mi carro" to listOf("🚗 My car","🚗 La mia auto","🚗 Ma voiture"),
 "Guardar ubicación" to listOf("Save location","Salva posizione","Enregistrer la position"),
 "Llevarme al carro" to listOf("Navigate to car","Vai alla mia auto","Aller à ma voiture"),
 "Quitar ubicación" to listOf("Clear location","Cancella posizione","Effacer la position"),
 "Ubicación guardada" to listOf("Location saved","Posizione salvata","Position enregistrée"),
 "No hay ubicación guardada" to listOf("No saved location","Nessuna posizione salvata","Aucune position enregistrée"),
 "Cerrar mapa" to listOf("Close map","Chiudi mappa","Fermer la carte")
)
private fun holidays(year:Int,month:Int):Map<Int,List<String>>{
 val result=mutableMapOf<Int,MutableList<String>>()
 fun add(day:Int,name:String){if(day in 1..31)result.getOrPut(day){mutableListOf()}.add(name)}
 fun fixed(m:Int,d:Int,name:String,observed:Boolean=false){
  if(month==m)add(d,name)
  if(observed){
   val c=Calendar.getInstance().apply{clear();set(year,m-1,d)}
   val dayOfWeek=c.get(Calendar.DAY_OF_WEEK)
   if(dayOfWeek==Calendar.SATURDAY)c.add(Calendar.DAY_OF_MONTH,-1)
   else if(dayOfWeek==Calendar.SUNDAY)c.add(Calendar.DAY_OF_MONTH,1)
   else return
   if(c.get(Calendar.YEAR)==year&&c.get(Calendar.MONTH)==month-1)add(c.get(Calendar.DAY_OF_MONTH),"$name (observado)")
  }
 }
 fun nth(m:Int,weekday:Int,n:Int,name:String){
  if(month!=m)return
  val c=Calendar.getInstance().apply{clear();set(year,m-1,1)}
  val shift=(weekday-c.get(Calendar.DAY_OF_WEEK)+7)%7
  add(1+shift+(n-1)*7,name)
 }
 fun last(m:Int,weekday:Int,name:String){
  if(month!=m)return
  val c=Calendar.getInstance().apply{clear();set(year,m,1);add(Calendar.DAY_OF_MONTH,-1)}
  val shift=(c.get(Calendar.DAY_OF_WEEK)-weekday+7)%7
  add(c.get(Calendar.DAY_OF_MONTH)-shift,name)
 }
 fixed(1,1,"Año Nuevo · EE. UU. y PR",true)
 nth(1,Calendar.MONDAY,3,"Martin Luther King Jr. · EE. UU. y PR")
 fixed(1,6,"Día de Reyes · PR")
 nth(2,Calendar.MONDAY,3,"Día de los Presidentes · EE. UU. y PR")
 fixed(3,22,"Día de la Abolición de la Esclavitud · PR")
 last(5,Calendar.MONDAY,"Memorial Day · EE. UU. y PR")
 fixed(6,19,"Juneteenth · EE. UU.",true)
 fixed(7,4,"Independencia de EE. UU. · EE. UU. y PR",true)
 fixed(7,25,"Día de la Constitución · PR")
 nth(9,Calendar.MONDAY,1,"Labor Day · EE. UU. y PR")
 nth(10,Calendar.MONDAY,2,"Columbus Day / Día de la Raza · EE. UU. y PR")
 fixed(11,11,"Día de los Veteranos · EE. UU. y PR",true)
 fixed(11,19,"Día del Descubrimiento de Puerto Rico · PR")
 nth(11,Calendar.THURSDAY,4,"Acción de Gracias · EE. UU. y PR")
 fixed(12,25,"Navidad · EE. UU. y PR",true)
 // Computus gregoriano: Domingo de Resurrección
 val a=year%19;val b=year/100;val c=year%100;val d=b/4;val e=b%4
 val f=(b+8)/25;val g=(b-f+1)/3;val h=(19*a+b-d-g+15)%30
 val i=c/4;val k=c%4;val l=(32+2*e+2*i-h-k+700)%7
 val m=(a+11*h+22*l)/451;val n=h+l-7*m+114
 val easter=Calendar.getInstance().apply{clear();set(year,n/31-1,n%31+1)}
 fun relative(days:Int,name:String){
  val date=easter.clone() as Calendar
  date.add(Calendar.DAY_OF_MONTH,days)
  if(date.get(Calendar.MONTH)==month-1)add(date.get(Calendar.DAY_OF_MONTH),name)
 }
 relative(-2,"Viernes Santo · PR")
 relative(0,"Domingo de Resurrección · PR")
 return result
}
private fun tr(lang:String,key:String):String {val i=when(lang){"en"->0;"it"->1;"fr"->2;else->-1};return if(i<0)key else translations[key]?.get(i)?:key}
@Composable fun Screen(activity:MainActivity){
 val backupRevision=activity.backupRevision.intValue
 val p=remember(backupRevision){activity.getSharedPreferences("apuntalo_alpha",0)}
 var tick by remember(backupRevision){mutableIntStateOf(0)}
 val currentMonth=monthKey()
 val todayKey=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
 remember(todayKey){resetDailyRecurringTasks(p);true}
 val tasks=remember(tick,backupRevision){read(p,"tasks")}
 remember(currentMonth){carryRecurringExpenses(p,currentMonth);true}
 val allExpenses=remember(tick,backupRevision){read(p,"expenses")}
 val expenses=remember(tick,currentMonth){JSONArray().also{out->for(i in 0 until allExpenses.length()){val e=allExpenses.getJSONObject(i);if(e.optString("month",monthKey(e.optLong("id",System.currentTimeMillis())))==currentMonth)out.put(e)}}}
 val shopping=remember(tick,backupRevision){read(p,"shopping")}
 var language by remember{mutableStateOf(p.getString("language","es")?:"es")}
 fun t(value:String)=tr(language,value)
 var mainTab by remember{mutableIntStateOf(0)}
 var tasksSubTab by remember{mutableIntStateOf(0)}
 val tab=when(mainTab){0->tasksSubTab;1->2;2->3;3->5;else->4}
 var monthOffset by remember{mutableIntStateOf(0)}
 var selectedDay by remember{mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH))}
 var add by remember{mutableStateOf(false)}
 var title by remember{mutableStateOf("")}
 var whenText by remember{mutableStateOf("")}
 var remindMe by remember{mutableStateOf(false)}
 var taskRecurring by remember{mutableStateOf(false)}
 var amount by remember{mutableStateOf("")}
 var expenseCategory by remember{mutableStateOf("Otros")}
 var expenseRecurring by remember{mutableStateOf(false)}
 var shoppingMode by remember{mutableStateOf(false)}
 var shoppingEntry by remember{mutableStateOf("")}
 var shoppingEditingId by remember{mutableLongStateOf(0L)}
 var configSubTab by remember{mutableIntStateOf(0)}
 var budgetEditing by remember{mutableStateOf(false)}
 var income by remember{mutableStateOf(p.getString("income_"+monthKey(),p.getString("income_default","0"))?:"0")}
 var editing by remember{mutableLongStateOf(0)}
 val themes=listOf("Midnight","OLED Black","Ocean","Emerald","Purple Haze","Burgundy","Graphite","Arctic","Pure White","Forest","Sunset","Cyberpunk","Dracula","Military","Crimson")
 var randomTheme by remember{mutableStateOf(p.getBoolean("random_theme",false))}
 var theme by remember{mutableStateOf(if(p.getBoolean("random_theme",false))themes.random().also{p.edit().putString("theme",it).apply()} else p.getString("theme","Midnight")?:"Midnight")}
 var notificationsEnabled by remember{mutableStateOf(p.getBoolean("notifications_enabled",true))}
 var wallpaperDim by remember{mutableFloatStateOf(p.getFloat("wallpaper_dim",1f))}
 val accent=when(theme){
 "Crimson"->Color(0xFFFF6079);"Ocean"->Color(0xFF59B8EA);"Emerald"->Color(0xFF55D6A1)
 "Purple Haze"->Color(0xFFBB8CF5);"Burgundy"->Color(0xFFE287A0);"Sunset"->Color(0xFFFFA16D)
 "Cyberpunk"->Color(0xFF00E5D0);"Dracula"->Color(0xFFBD93F9);"Military"->Color(0xFFC0B887)
 "Forest"->Color(0xFF88C88A);"Graphite"->Color(0xFFAAB5C7);"Arctic"->Color(0xFF287FA5)
 else->teal
 }
 val background=when(theme){
 "Crimson"->Color(0xFF260B17);"OLED Black"->Color.Black;"Ocean"->Color(0xFF091D32)
 "Emerald"->Color(0xFF09251F);"Purple Haze"->Color(0xFF1C102E)
 "Burgundy"->Color(0xFF30131F);"Graphite"->Color(0xFF1B2029)
 "Arctic"->Color(0xFFE6F0F5);"Pure White"->Color(0xFFF7F9FC)
 "Forest"->Color(0xFF10271C);"Sunset"->Color(0xFF35202A)
 "Cyberpunk"->Color(0xFF100D28);"Dracula"->Color(0xFF282A36)
 "Military"->Color(0xFF24291F);else->bg
 }
 val panel=when(theme){
 "Crimson"->Color(0xFF451626);"Ocean"->Color(0xFF15334D);"Emerald"->Color(0xFF163E32)
 "Purple Haze"->Color(0xFF342047);"Burgundy"->Color(0xFF512438)
 "Forest"->Color(0xFF254332);"Sunset"->Color(0xFF563040)
 "Cyberpunk"->Color(0xFF252044);"Dracula"->Color(0xFF44475A)
 "Military"->Color(0xFF3C4232);"OLED Black"->Color(0xFF141414)
 "Arctic","Pure White"->Color(0xFF33475A);else->card
 }
 val glassPanel=panel.copy(alpha=0.78f)
 val foreground=if(theme=="Pure White"||theme=="Arctic")Color.Black else Color.White
 val wallpaperVersion=activity.wallpaperRevision.intValue
 val wallpaper=remember(wallpaperVersion){runCatching{BitmapFactory.decodeFile(File(activity.filesDir,"wallpaper.jpg").absolutePath)?.asImageBitmap()}.getOrNull()}
 fun refresh(){tick++}
 fun taskSave(a:JSONArray){save(p,"tasks",a);if(calendarAllowed(activity))runCatching{syncDeviceCalendar(activity,true)};refresh()}
 fun expenseSave(a:JSONArray){save(p,"expenses",a);refresh()}
 fun shoppingSave(a:JSONArray){save(p,"shopping",a);refresh()}
 MaterialTheme(colorScheme=if(foreground==Color.Black)lightColorScheme(primary=accent)else darkColorScheme(primary=accent)){
 Box(Modifier.fillMaxSize().background(background)){
 if(wallpaper==null){
 Canvas(Modifier.fillMaxSize()){
 val w=size.width;val h=size.height
 drawRect(brush=Brush.verticalGradient(listOf(background,background,accent.copy(alpha=0.26f)),startY=0f,endY=h))
 val wave1=Path().apply{
 moveTo(0f,h*.12f)
 cubicTo(w*.22f,h*.44f,w*.43f,h*.02f,w*.65f,h*.30f)
 cubicTo(w*.83f,h*.53f,w*.91f,h*.20f,w,h*.26f)
 lineTo(w,h);lineTo(0f,h);close()
 }
 drawPath(wave1,brush=Brush.verticalGradient(listOf(accent.copy(alpha=.05f),accent.copy(alpha=.72f),accent.copy(alpha=.10f)),startY=h*.12f,endY=h))
 val wave2=Path().apply{
 moveTo(0f,h*.55f)
 cubicTo(w*.28f,h*.24f,w*.41f,h*.85f,w*.66f,h*.58f)
 cubicTo(w*.86f,h*.37f,w*.92f,h*.66f,w,h*.51f)
 lineTo(w,h);lineTo(0f,h);close()
 }
 drawPath(wave2,brush=Brush.verticalGradient(listOf(accent.copy(alpha=.04f),accent.copy(alpha=.42f),background.copy(alpha=.86f)),startY=h*.28f,endY=h))
 val line1=Path().apply{
 moveTo(0f,h*.78f)
 cubicTo(w*.22f,h*.91f,w*.43f,h*.94f,w*.65f,h*.78f)
 cubicTo(w*.83f,h*.66f,w*.91f,h*.62f,w,h*.60f)
 }
 drawPath(line1,color=accent.copy(alpha=.84f),style=Stroke(width=2.dp.toPx()))
 val line2=Path().apply{
 moveTo(0f,h*.89f)
 cubicTo(w*.28f,h*.82f,w*.41f,h*1.04f,w*.66f,h*.91f)
 cubicTo(w*.86f,h*.82f,w*.92f,h*.87f,w,h*.83f)
 }
 drawPath(line2,color=accent.copy(alpha=.55f),style=Stroke(width=1.5.dp.toPx()))
 }
 }else Box(Modifier.fillMaxSize().background(Color.White)){
 Image(bitmap=wallpaper,contentDescription="Fondo personalizado",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize().alpha(wallpaperDim))
 }
 Column(Modifier.fillMaxSize().background(Color.Transparent).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=18.dp, vertical=12.dp)){
 Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
 Image(painter=painterResource(id=R.drawable.apuntalo_launcher_logo),contentDescription="Logo original de la libreta y el lápiz",contentScale=ContentScale.Fit,modifier=Modifier.size(96.dp))
 Text("APÚNTALO",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Black,color=if(theme=="Pure White"||theme=="Arctic")Color(0xFF14252E) else Color.White)
 Text(t("que se te olvida"),color=accent)
 }
 Text(t("Creada por Gilmar"),style=MaterialTheme.typography.labelSmall,color=foreground)
 Spacer(Modifier.height(12.dp))
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)){
 listOf(t("☑ Tareas"),t("▦ Calendario"),t("◈ Gastos"),t("🚗 Mi carro"),t("⚙ Config")).forEachIndexed{idx,name->
 Button(onClick={mainTab=idx},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=1.dp),colors=ButtonDefaults.buttonColors(containerColor=if(mainTab==idx)accent else panel)){Text(name,color=if(mainTab==idx)Color.Black else Color.White,fontSize=10.sp,maxLines=1,softWrap=false)}
 }
 }
 if(mainTab==0){
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   listOf(t("☑ Tareas"),t("🛒 Compras")).forEachIndexed{idx,name->
    OutlinedButton(onClick={tasksSubTab=idx},colors=ButtonDefaults.outlinedButtonColors(containerColor=if(tasksSubTab==idx)accent else panel),contentPadding=PaddingValues(horizontal=14.dp)){
     Text(name,color=if(tasksSubTab==idx)Color.Black else Color.White)
    }
   }
  }
 }
 Spacer(Modifier.height(14.dp))
 when(tab){
 0,1,2->{
 if(tab==1){
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
 Text(t("Listas de compras"),modifier=Modifier.weight(1f),color=foreground,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 OutlinedButton(onClick={shareList(activity,t("Listas de compras"),shopping)}){Text("↗ "+t("Compartir"))}
 }
 Row(verticalAlignment=Alignment.CenterVertically){
 OutlinedTextField(shoppingEntry,{shoppingEntry=it},modifier=Modifier.weight(1f),label={Text(t("Añadir producto"))},singleLine=true)
 Spacer(Modifier.width(6.dp))
 Button(onClick={if(shoppingEntry.isNotBlank()){val id=if(shoppingEditingId!=0L)shoppingEditingId else System.currentTimeMillis();val previous=(0 until shopping.length()).firstOrNull{shopping.getJSONObject(it).optLong("id")==id}?.let{shopping.getJSONObject(it)};val updated=if(shoppingEditingId!=0L)remove(shopping,id)else shopping;updated.put(JSONObject().put("id",id).put("title",shoppingEntry.trim()).put("done",previous?.optBoolean("done")?:false));shoppingSave(updated);shoppingEntry="";shoppingEditingId=0L}}){Text("+")}
 }
 Column{(0 until shopping.length()).map{shopping.getJSONObject(it)}.forEach{item->
 Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
 Checkbox(checked=item.optBoolean("done"),onCheckedChange={item.put("done",it);shoppingSave(shopping)})
 Text(item.optString("title"),modifier=Modifier.weight(1f).clickable{shoppingEditingId=item.optLong("id");shoppingEntry=item.optString("title")},color=foreground)
 TextButton(onClick={shareTask(activity,item.optString("title"),0L)}){Text(t("Compartir"),color=accent)}
 TextButton(onClick={shoppingSave(remove(shopping,item.optLong("id")))}){Text(t("Borrar"))}
 }
 }}
 }else{
 if(tab==2){
 val cal=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,monthOffset)}
 val month=SimpleDateFormat("MMMM yyyy",Locale("es","PR")).format(cal.time).replaceFirstChar{it.uppercase()}
 val offset=(cal.get(Calendar.DAY_OF_WEEK)+5)%7
 val days=cal.getActualMaximum(Calendar.DAY_OF_MONTH)
 val monthHolidays=holidays(cal.get(Calendar.YEAR),cal.get(Calendar.MONTH)+1)
 val calendarRevision=activity.calendarRevision.intValue
 OutlinedButton(onClick={activity.synchronizeCalendar()}){Text(t("Sincronizar calendario del teléfono"))}
 Text(t("Google Calendar y Samsung Calendar: requiere permiso y un calendario editable en el dispositivo."),color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)

 Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=glassPanel)){
 Column(Modifier.padding(14.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={monthOffset--;selectedDay=1}){Text("‹",style=MaterialTheme.typography.headlineSmall)};Text(month,modifier=Modifier.weight(1f),color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);TextButton(onClick={monthOffset++;selectedDay=1}){Text("›",style=MaterialTheme.typography.headlineSmall)}}
 Row {listOf("L","M","X","J","V","S","D").forEach{Text(it,modifier=Modifier.weight(1f),color=accent,style=MaterialTheme.typography.labelMedium)}}
 for(week in 0 until ((offset+days+6)/7)){
 Row {for(col in 0..6){val day=week*7+col-offset+1
 if(day in 1..days){
 val isHoliday=monthHolidays.containsKey(day)
 val hasTask=(0 until tasks.length()).any{idx->val time=tasks.getJSONObject(idx).optLong("time");if(time<=0)false else Calendar.getInstance().apply{timeInMillis=time}.let{it.get(Calendar.YEAR)==cal.get(Calendar.YEAR)&&it.get(Calendar.MONTH)==cal.get(Calendar.MONTH)&&it.get(Calendar.DAY_OF_MONTH)==day}}
 Box(Modifier.weight(1f).padding(2.dp).background(if(selectedDay==day)accent else if(isHoliday)Color(0xFFFFB86B).copy(alpha=0.26f) else if(hasTask)accent.copy(alpha=0.2f) else Color.Transparent,RoundedCornerShape(10.dp)).clickable{selectedDay=day}.height(43.dp),contentAlignment=Alignment.Center){Text("$day"+(if(hasTask)" •" else "")+(if(isHoliday)" ★" else ""),color=if(selectedDay==day)Color.Black else Color.White,fontSize=12.sp,maxLines=1)}
 }else Box(Modifier.weight(1f).height(43.dp)){}
 }}
 }
 Spacer(Modifier.height(8.dp))
 Text(t("Día \$selectedDay · Toca un día para consultar sus tareas").replace("\$selectedDay",selectedDay.toString()),color=Color.White.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 }
 }
 }

 Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=glassPanel)){Column(Modifier.padding(18.dp)){Text(t("TU PRODUCTIVIDAD"),color=accent,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold);Spacer(Modifier.height(7.dp));val done=(0 until tasks.length()).count{tasks.getJSONObject(it).optBoolean("done")};Text("$done de ${tasks.length()} completadas",color=Color.White,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={if(tasks.length()==0)0f else done.toFloat()/tasks.length()},modifier=Modifier.fillMaxWidth().height(7.dp),color=accent,trackColor=Color.White.copy(alpha=0.12f))}};Row {Text(if(tab==0)t("Mis tareas") else t("Próximos recordatorios"),color=foreground,modifier=Modifier.weight(1f));TextButton(onClick={shareList(activity,t("Mis tareas"),tasks)}){Text("↗ "+t("Compartir"))};Button(onClick={title="";whenText="";remindMe=false;taskRecurring=false;editing=0;add=true}){Text(t("+ Tarea"))}}
 val sorted=(0 until tasks.length()).map{tasks.getJSONObject(it)}.filter{task->
  if(tab==0)true
  else{
   val taskTime=task.optLong("time")
   if(taskTime<=0L)false
   else{
    val taskDate=Calendar.getInstance().apply{timeInMillis=taskTime}
    val selectedDate=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,monthOffset)}
    taskDate.get(Calendar.YEAR)==selectedDate.get(Calendar.YEAR)&&
     taskDate.get(Calendar.MONTH)==selectedDate.get(Calendar.MONTH)&&
     taskDate.get(Calendar.DAY_OF_MONTH)==selectedDay
   }
  }
 }.sortedWith(compareBy<JSONObject>({it.optBoolean("done")},{if(it.optLong("time")==0L)Long.MAX_VALUE else it.optLong("time")}))
 Column {(sorted).forEach{o->
  Card(Modifier.fillMaxWidth().padding(vertical=5.dp),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=glassPanel)){
  Row(Modifier.padding(10.dp)){
   Checkbox(checked=o.optBoolean("done"),onCheckedChange={done->
    o.put("done",done);if(done)cancelAlarm(activity,o.optLong("id")) else if(o.optLong("time")>System.currentTimeMillis())notifyAlarm(activity,o.optLong("id"),o.optString("title"),o.optLong("time"));taskSave(tasks)
   })
   Column(Modifier.weight(1f).clickable{editing=o.optLong("id");title=o.optString("title");whenText=if(o.optLong("time")>0)fmt(o.optLong("time")) else "";remindMe=o.optLong("time")>0;taskRecurring=o.optBoolean("recurring",false);add=true}){
    Text(o.optString("title")+(if(o.optBoolean("recurring",false))" · ↻" else ""),color=Color.White,fontWeight=FontWeight.SemiBold)
    if(o.optLong("time")>0)Text(fmt(o.optLong("time")),color=teal,style=MaterialTheme.typography.bodySmall)
   }
   TextButton(onClick={shareTask(activity,o.optString("title"),o.optLong("time"))},contentPadding=PaddingValues(horizontal=4.dp)){Text(t("Compartir"),color=accent)}
   TextButton(onClick={cancelAlarm(activity,o.optLong("id"));taskSave(remove(tasks,o.optLong("id")))}){Text(t("Borrar"))}
  }}
 }}
 }
 }
 3->{
 Text(t("Resumen financiero · ")+SimpleDateFormat("MMMM yyyy",Locale("es","PR")).format(Date()),color=foreground,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 OutlinedTextField(value=income,onValueChange={income=it;p.edit().putString("income_"+currentMonth,it).putString("income_default",it).apply()},label={Text(t("Ingreso mensual"))},placeholder={Text(t("Ejemplo: $2,000.00"))},singleLine=true)
 val total=expenseTotal(expenses).toDouble()
 Text("Gastos: $%.2f".format(total),color=foreground)
 Card(Modifier.fillMaxWidth().padding(vertical=12.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=glassPanel)){Column(Modifier.padding(20.dp)){Text(t("BALANCE DISPONIBLE"),color=accent,style=MaterialTheme.typography.labelMedium);Text("$%.2f".format(((moneyValue(income)?.toDouble()?:0.0)-total)),color=Color.White,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Black);Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={if((moneyValue(income)?.toDouble()?:0.0)<=0)0f else (total/(moneyValue(income)?.toDouble()?:1.0)).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(7.dp),color=accent,trackColor=Color.White.copy(alpha=0.12f))}}
 val categories=listOf("Hogar","Comida","Transporte","Familia","Salud","Otros")
 val chartColors=listOf(Color(0xFF62C4B4),Color(0xFFFFB86B),Color(0xFF8DA8FF),Color(0xFFCE8DF4),Color(0xFFFF7F91),Color(0xFFADB6C5))
 val sums=categories.map{category->(0 until expenses.length()).sumOf{idx->val e=expenses.getJSONObject(idx);if(e.optString("category","Otros")==category)(moneyValue(e.optString("amount","0"))?.toDouble()?:0.0).coerceAtLeast(0.0) else 0.0}}
 Card(Modifier.fillMaxWidth().padding(vertical=10.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=glassPanel)){
 Column(Modifier.padding(18.dp)){
 Text(t("DISTRIBUCIÓN DE GASTOS"),color=accent,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
 Spacer(Modifier.height(14.dp))
 Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
 Canvas(Modifier.size(170.dp)){
 val stroke=30.dp.toPx()
 val inset=stroke/2f
 val arcSize=Size(size.width-stroke,size.height-stroke)
 if(total<=0.0)drawArc(Color.White.copy(alpha=0.12f),0f,360f,false,topLeft=Offset(inset,inset),size=arcSize,style=Stroke(stroke,cap=StrokeCap.Butt))
 else{
 var angle=-90f
 sums.forEachIndexed{idx,v->
 if(v>0){val sweep=(v/total*360.0).toFloat();drawArc(chartColors[idx],angle,sweep,false,topLeft=Offset(inset,inset),size=arcSize,style=Stroke(stroke));angle+=sweep}
 }
 }
 }
 Column(horizontalAlignment=Alignment.CenterHorizontally){
 Text("$%.2f".format(total),color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 Text(t("Gastado"),color=Color.White.copy(alpha=0.7f),style=MaterialTheme.typography.labelMedium)
 }
 }
 Spacer(Modifier.height(12.dp))
 categories.forEachIndexed{idx,cat->if(sums[idx]>0){
 Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){
 Box(Modifier.size(11.dp).background(chartColors[idx],RoundedCornerShape(3.dp)))
 Spacer(Modifier.width(9.dp))
 Text(cat,color=Color.White,modifier=Modifier.weight(1f))
 Text("$%.2f".format(sums[idx]),color=Color.White)
 }
 }}
 if(total<=0.0)Text(t("Registra un gasto para ver tu gráfica."),color=Color.White.copy(alpha=0.7f))
 }
 }
 Card(Modifier.fillMaxWidth().padding(vertical=10.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=glassPanel)){
 Column(Modifier.padding(16.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){
 Text(t("PRESUPUESTOS"),color=accent,modifier=Modifier.weight(1f),fontWeight=FontWeight.Bold)
 TextButton(onClick={budgetEditing=!budgetEditing}){Text(if(budgetEditing)"Listo" else "Editar límites")}
 }
 categories.forEachIndexed{idx,cat->
 val key="budget_"+currentMonth+"_"+cat
 var limitText by remember(cat){mutableStateOf(p.getString(key,"")?:"")}
 val limit=limitText.toDoubleOrNull()?:0.0
 Column(Modifier.padding(vertical=5.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){
 Text(cat,color=Color.White,modifier=Modifier.weight(1f))
 Text("$%.2f".format(sums[idx])+(if(limit>0)" / $%.2f".format(limit)else""),color=if(limit>0 && sums[idx]>limit)Color(0xFFFF7F91) else Color.White)
 }
 if(budgetEditing){
 OutlinedTextField(limitText,onValueChange={limitText=it;p.edit().putString(key,it).apply()},label={Text("Límite mensual de $cat")},singleLine=true)
 }else if(limit>0){
 LinearProgressIndicator(progress={(sums[idx]/limit).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(6.dp),color=if(sums[idx]>=limit)Color(0xFFFF7F91)else chartColors[idx],trackColor=Color.White.copy(alpha=0.12f))
 if(sums[idx]>=limit*0.8)Text(if(sums[idx]>limit)"Presupuesto excedido"else"Te acercas al límite",color=Color(0xFFFFB86B),style=MaterialTheme.typography.labelSmall)
 }
 }
 }
 }
 }
 Button(onClick={title="";amount="";expenseCategory="Otros";expenseRecurring=false;editing=0;add=true}){Text(t("+ Gasto"))}
 Column {(0 until expenses.length()).map{expenses.getJSONObject(it)}.forEach{o->
 Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween){
 Column(Modifier.weight(1f).clickable{editing=o.optLong("id");title=o.optString("title");amount=o.optDouble("amount").toString();expenseCategory=o.optString("category","Otros");expenseRecurring=o.optBoolean("recurring",false);add=true}){Text(o.optString("title"),color=foreground);Text("$%.2f".format(o.optDouble("amount"))+(if(o.optBoolean("recurring",false))" · ↻ Recurrente" else ""),color=accent)}
 TextButton(onClick={expenseSave(remove(allExpenses,o.optLong("id")) )}){Text(t("Borrar"))}
 }
 }}
 }
 4->{
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
 listOf("Idioma","Notificaciones","Apariencia").forEachIndexed{idx,label->
 OutlinedButton(onClick={configSubTab=idx},colors=ButtonDefaults.outlinedButtonColors(containerColor=if(configSubTab==idx)accent else panel),contentPadding=PaddingValues(horizontal=9.dp)){
 Text(t(label),color=if(configSubTab==idx)Color.Black else Color.White)
 }
 }
 }
 if(configSubTab==0){
 HorizontalDivider()
 Text("Respaldo de APÚNTALO",color=foreground,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
 Text("Guarda o recupera tareas, gastos, compras, ajustes y wallpaper. Elige Google Drive en el selector de archivos.",color=foreground)
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
 Button(onClick={activity.createBackup()}){Text("Crear respaldo")}
 OutlinedButton(onClick={activity.restoreBackup()}){Text("Restaurar")}
 }
 }
 if(configSubTab==1){
 Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
 Column(Modifier.weight(1f)){
 Text(t("Notificaciones"),color=foreground,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
 Text(t("Recordatorios de tareas con fecha y hora"),color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 }
 Switch(checked=notificationsEnabled,onCheckedChange={enabled->
 notificationsEnabled=enabled
 p.edit().putBoolean("notifications_enabled",enabled).apply()
 for(i in 0 until tasks.length()){
 val task=tasks.getJSONObject(i)
 val id=task.optLong("id")
 cancelAlarm(activity,id)
 if(enabled && !task.optBoolean("done") && task.optLong("time")>System.currentTimeMillis())notifyAlarm(activity,id,task.optString("title"),task.optLong("time"))
 }
 })
 }
 }
 if(configSubTab==0){
 Text(t("Idioma"),color=foreground,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
 var languageExpanded by remember{mutableStateOf(false)}
 Box {
  OutlinedButton(onClick={languageExpanded=true}){Text(when(language){"en"->"English";"it"->"Italiano";"fr"->"Français";else->"Español"})}
  DropdownMenu(expanded=languageExpanded,onDismissRequest={languageExpanded=false}){
   listOf("es" to "Español","en" to "English","it" to "Italiano","fr" to "Français").forEach{(code,name)->
    DropdownMenuItem(text={Text(name)},onClick={language=code;p.edit().putString("language",code).apply();languageExpanded=false})
   }
  }
 }
 }
 if(configSubTab==2){
 Text(t("Wallpaper personalizado"),color=foreground,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
 Text(t("Usa una foto de tu galería como fondo de la aplicación"),color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
 Button(onClick={activity.chooseWallpaper()}){Text(t("⬆ Subir wallpaper"))}
 if(wallpaper!=null)OutlinedButton(onClick={activity.removeWallpaper()}){Text(t("Quitar fondo"))}
 }
 HorizontalDivider()
 Text("Dim: ${(wallpaperDim*100).toInt()} %",color=foreground)
 Slider(value=wallpaperDim,onValueChange={wallpaperDim=it;p.edit().putFloat("wallpaper_dim",it).apply()},valueRange=0f..1f)
 Text(t("Tema visual"),color=foreground)
 Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
 Column(Modifier.weight(1f)){
 Text(t("Tema aleatorio"),color=foreground,style=MaterialTheme.typography.titleMedium)
 Text(t("Un tema diferente al abrir la app"),color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 }
 Switch(checked=randomTheme,onCheckedChange={enabled->
 randomTheme=enabled
 p.edit().putBoolean("random_theme",enabled).apply()
 if(enabled){theme=themes.filter{it!=theme}.random();p.edit().putString("theme",theme).apply()}
 })
 }
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
 themes.chunked(3).forEach{group->
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
 group.forEach{name->
 Box(Modifier.weight(1f)){
 ThemeThumbnail(name,theme==name&&!randomTheme){
 randomTheme=false;theme=name
 p.edit().putBoolean("random_theme",false).putString("theme",name).apply()
 }
 }
 }
 repeat(3-group.size){Spacer(Modifier.weight(1f))}
 }
 }
 }
 }
 }
 else->{
 val revision=activity.carRevision.intValue
 val carLat=remember(revision){p.getString("car_lat",null)}
 val carLon=remember(revision){p.getString("car_lon",null)}
 Text(t("🚗 Mi carro"),color=foreground,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 Spacer(Modifier.height(16.dp))
 Text(if(carLat!=null&&carLon!=null)t("Ubicación guardada") else t("No hay ubicación guardada"),color=foreground)
 Spacer(Modifier.height(12.dp))
 Button(onClick={activity.rememberCar()}){Text(t("Guardar ubicación"))}
 if(carLat!=null&&carLon!=null){
  Button(onClick={
   val mapsUrl=Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$carLat,$carLon&travelmode=walking")
   val mapsIntent=Intent(Intent.ACTION_VIEW,mapsUrl).apply{setPackage("com.google.android.apps.maps")}
   try{activity.startActivity(mapsIntent)}catch(_:android.content.ActivityNotFoundException){
    activity.startActivity(Intent(Intent.ACTION_VIEW,mapsUrl))
   }
  }){Text(t("Llevarme al carro"))}
  OutlinedButton(onClick={activity.clearCar()}){Text(t("Quitar ubicación"))}
 }
 }
 }
 }
 }
 if(add)AlertDialog(onDismissRequest={add=false},title={Text(if(tab==3)"Gasto" else "Tarea")},text={
 Column {
 OutlinedTextField(title,{title=it},label={Text(t("Descripción"))})
 if(tab==3){
 OutlinedTextField(amount,{amount=it},label={Text(t("Cantidad"))},isError=amount.isNotBlank()&&moneyValue(amount)==null)
 if(amount.isNotBlank()&&moneyValue(amount)==null)Text("Cantidad inválida",color=Color.Red)
 Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=expenseRecurring,onCheckedChange={expenseRecurring=it});Text("Repetir este gasto cada mes") }
 Text(t("Categoría"),style=MaterialTheme.typography.labelMedium)
 val cats=listOf("Hogar","Comida","Transporte","Familia","Salud","Otros")
 cats.chunked(3).forEach{group->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
 group.forEach{cat->FilterChip(selected=expenseCategory==cat,onClick={expenseCategory=cat},label={Text(cat,style=MaterialTheme.typography.labelSmall)})}
 }}
 }
 else {
 Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=taskRecurring,onCheckedChange={taskRecurring=it});Text("Repetir esta tarea todos los días")}
 Row(verticalAlignment=Alignment.CenterVertically){
 Checkbox(checked=remindMe,onCheckedChange={remindMe=it;if(!it)whenText=""})
 Text(t("Recordarme con notificación"),modifier=Modifier.clickable{remindMe=!remindMe;if(!remindMe)whenText=""})
 }
 if(remindMe){
 val picked=remember(whenText){(if(whenText.isBlank())null else parse(whenText))?.let{Date(it)}?:Date()}
 val cal=Calendar.getInstance().apply{time=picked}
 OutlinedButton(onClick={
  android.app.DatePickerDialog(activity,{_,year,month,day->
   val next=Calendar.getInstance().apply{time=picked;set(Calendar.YEAR,year);set(Calendar.MONTH,month);set(Calendar.DAY_OF_MONTH,day)}
   whenText=fmt(next.timeInMillis)
  },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
 }){Text(t("Elegir fecha"))}
 OutlinedButton(onClick={
  android.app.TimePickerDialog(activity,{_,hour,minute->
   val next=Calendar.getInstance().apply{time=picked;set(Calendar.HOUR_OF_DAY,hour);set(Calendar.MINUTE,minute);set(Calendar.SECOND,0)}
   whenText=fmt(next.timeInMillis)
  },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),false).show()
 }){Text(t("Elegir hora"))}
 if(whenText.isNotBlank())Text(whenText,style=MaterialTheme.typography.bodySmall)
 }
 }
 }
 },confirmButton={TextButton(onClick={
 if(title.isNotBlank() && (tab!=3 || moneyValue(amount)!=null)){
 val id=if(editing!=0L)editing else System.currentTimeMillis()
 if(tab==3){
 val a=if(editing!=0L)remove(allExpenses,id)else allExpenses
 val old=(0 until allExpenses.length()).firstOrNull{allExpenses.getJSONObject(it).optLong("id")==id}?.let{allExpenses.getJSONObject(it)}
 a.put(JSONObject().put("id",id).put("title",title).put("amount",moneyValue(amount)!!.toDouble()).put("category",expenseCategory).put("month",currentMonth).put("recurring",expenseRecurring).put("recurringOrigin",old?.optLong("recurringOrigin",id)?:id))
 expenseSave(a)
 }else{
 val t=if(!remindMe)0L else if(whenText.isBlank())-1L else parse(whenText)?:-1L
 if(t>=0){
 val old=(0 until tasks.length()).firstOrNull{tasks.getJSONObject(it).optLong("id")==id}?.let{tasks.getJSONObject(it)}
 val a=if(editing!=0L)remove(tasks,id)else tasks
 a.put(JSONObject().put("id",id).put("title",title).put("time",t).put("done",old?.optBoolean("done")?:false).put("recurring",taskRecurring).put("lastDailyReset",if(taskRecurring)todayKey else ""))
 cancelAlarm(activity,id)
 if(t>System.currentTimeMillis()&&old?.optBoolean("done")!=true)notifyAlarm(activity,id,title,t)
 taskSave(a)
 }
 }
 add=false
 }
 }){Text(t("Guardar"))}},dismissButton={TextButton(onClick={add=false}){Text(t("Cancelar"))}})
}
}

class TaskWidgetProvider:AppWidgetProvider(){
 companion object {private const val ACTION_TAB="com.gilmar.apuntalo.WIDGET_TAB";private const val PREF_TAB="widget_tab"}
 override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){
  val p=context.getSharedPreferences("apuntalo_alpha",0)
  val tasks=read(p,"tasks")
  val shopping=read(p,"shopping")
  val expenses=read(p,"expenses")
  val tab=p.getInt(PREF_TAB,0).coerceIn(0,3)
  val pending=(0 until tasks.length()).map{tasks.getJSONObject(it)}.filter{!it.optBoolean("done")}.sortedBy{if(it.optLong("time")>0)it.optLong("time")else Long.MAX_VALUE}
  val shoppingItems=(0 until shopping.length()).map{shopping.getJSONObject(it)}.filter{!it.optBoolean("done")}
  val upcoming=pending.filter{it.optLong("time")>0}
  val total=(0 until expenses.length()).sumOf{expenses.getJSONObject(it).optDouble("amount")}
  val labels=listOf("Tareas","Compras","Agenda","Gastos")
  val lines=when(tab){
   0->if(pending.isEmpty())"Todo al día ✓" else pending.take(4).joinToString("\n"){"• "+it.optString("title")}
   1->if(shoppingItems.isEmpty())"Lista de compras vacía" else shoppingItems.take(4).joinToString("\n"){"• "+it.optString("title")}
   2->if(upcoming.isEmpty())"Sin recordatorios próximos" else upcoming.take(4).joinToString("\n"){"• "+it.optString("title")+" · "+fmt(it.optLong("time"))}
   else->"Total registrado: $"+String.format(Locale.US,"%.2f",total)+"\n"+(0 until expenses.length()).map{expenses.getJSONObject(it)}.takeLast(3).joinToString("\n"){"• "+it.optString("title")+" · $"+String.format(Locale.US,"%.2f",it.optDouble("amount"))}
  }
  ids.forEach{id->
   val view=RemoteViews(context.packageName,com.gilmar.apuntalo.R.layout.apuntalo_widget)
   view.setTextViewText(com.gilmar.apuntalo.R.id.widget_tasks,lines)
   view.setTextViewText(com.gilmar.apuntalo.R.id.widget_header,"APÚNTALO · "+labels[tab])
   val tabIds=intArrayOf(com.gilmar.apuntalo.R.id.widget_tab_tasks,com.gilmar.apuntalo.R.id.widget_tab_shopping,com.gilmar.apuntalo.R.id.widget_tab_agenda,com.gilmar.apuntalo.R.id.widget_tab_expenses)
   tabIds.forEachIndexed{index,viewId->
    view.setTextColor(viewId,if(index==tab)android.graphics.Color.rgb(64,198,183) else android.graphics.Color.WHITE)
    val intent=Intent(context,TaskWidgetProvider::class.java).setAction(ACTION_TAB).putExtra("tab",index).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
    val pi=PendingIntent.getBroadcast(context,id*10+index,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    view.setOnClickPendingIntent(viewId,pi)
   }
   val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   view.setOnClickPendingIntent(com.gilmar.apuntalo.R.id.widget_header,open)
   manager.updateAppWidget(id,view)
  }
 }
 override fun onReceive(context:Context,intent:Intent){
  super.onReceive(context,intent)
  if(intent.action==ACTION_TAB){
   context.getSharedPreferences("apuntalo_alpha",0).edit().putInt(PREF_TAB,intent.getIntExtra("tab",0).coerceIn(0,3)).apply()
  }
  if(intent.action==ACTION_TAB||intent.action=="com.gilmar.apuntalo.REFRESH_WIDGET"){
   val mgr=AppWidgetManager.getInstance(context)
   onUpdate(context,mgr,mgr.getAppWidgetIds(android.content.ComponentName(context,TaskWidgetProvider::class.java)))
  }
 }
}
