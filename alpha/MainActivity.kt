package com.gilmar.apuntalo

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.widget.RemoteViews
import android.content.*
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
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
private fun shareTask(ctx:Context,title:String,time:Long){
 val message="📌 APÚNTALO: "+title+(if(time>0L)"\\n📅 "+fmt(time) else "")
 val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,message)}
 ctx.startActivity(Intent.createChooser(intent,"Compartir tarea"))
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
 private val wallpaperPicker=registerForActivityResult(ActivityResultContracts.GetContent()){uri->
  if(uri!=null)try{
   contentResolver.openInputStream(uri)?.use{input->File(filesDir,"wallpaper.jpg").outputStream().use{output->input.copyTo(output)}}
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
@Composable fun Screen(activity:MainActivity){
 val p=remember{activity.getSharedPreferences("apuntalo_alpha",0)}
 var tick by remember{mutableIntStateOf(0)}
 val tasks=remember(tick){read(p,"tasks")}
 val allExpenses=remember(tick){read(p,"expenses")}
 val currentMonth=monthKey()
 val expenses=remember(tick,currentMonth){JSONArray().also{out->for(i in 0 until allExpenses.length()){val e=allExpenses.getJSONObject(i);if(e.optString("month",monthKey(e.optLong("id",System.currentTimeMillis())))==currentMonth)out.put(e)}}}
 val shopping=remember(tick){read(p,"shopping")}
 var tab by remember{mutableIntStateOf(0)}
 var monthOffset by remember{mutableIntStateOf(0)}
 var selectedDay by remember{mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH))}
 var add by remember{mutableStateOf(false)}
 var title by remember{mutableStateOf("")}
 var whenText by remember{mutableStateOf("")}
 var amount by remember{mutableStateOf("")}
 var expenseCategory by remember{mutableStateOf("Otros")}
 var shoppingMode by remember{mutableStateOf(false)}
 var shoppingEntry by remember{mutableStateOf("")}
 var budgetEditing by remember{mutableStateOf(false)}
 var income by remember{mutableStateOf(p.getString("income_"+monthKey(),p.getString("income_default","0"))?:"0")}
 var editing by remember{mutableLongStateOf(0)}
 val themes=listOf("Midnight","OLED Black","Ocean","Emerald","Purple Haze","Burgundy","Graphite","Arctic","Pure White","Forest","Sunset","Cyberpunk","Dracula","Military","Crimson")
 var randomTheme by remember{mutableStateOf(p.getBoolean("random_theme",false))}
 var theme by remember{mutableStateOf(if(p.getBoolean("random_theme",false))themes.random().also{p.edit().putString("theme",it).apply()} else p.getString("theme","Midnight")?:"Midnight")}
 var notificationsEnabled by remember{mutableStateOf(p.getBoolean("notifications_enabled",true))}
 var wallpaperDim by remember{mutableFloatStateOf(p.getFloat("wallpaper_dim",0.55f))}
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
 val foreground=if(theme=="Pure White"||theme=="Arctic")Color.Black else Color.White
 val wallpaperVersion=activity.wallpaperRevision.intValue
 val wallpaper=remember(wallpaperVersion){runCatching{BitmapFactory.decodeFile(File(activity.filesDir,"wallpaper.jpg").absolutePath)?.asImageBitmap()}.getOrNull()}
 fun refresh(){tick++}
 fun taskSave(a:JSONArray){save(p,"tasks",a);refresh()}
 fun expenseSave(a:JSONArray){save(p,"expenses",a);refresh()}
 fun shoppingSave(a:JSONArray){save(p,"shopping",a);refresh()}
 MaterialTheme(colorScheme=if(foreground==Color.Black)lightColorScheme(primary=accent)else darkColorScheme(primary=accent)){
 Box(Modifier.fillMaxSize().background(background)){
 if(wallpaper==null){
 Canvas(Modifier.fillMaxSize()){
 val w=size.width;val h=size.height
 drawRect(brush=Brush.verticalGradient(listOf(background,background,accent.copy(alpha=0.26f)),startY=0f,endY=h))
 val wave1=Path().apply{
 moveTo(0f,h*.78f)
 cubicTo(w*.22f,h*.91f,w*.43f,h*.94f,w*.65f,h*.78f)
 cubicTo(w*.83f,h*.66f,w*.91f,h*.62f,w,h*.60f)
 lineTo(w,h);lineTo(0f,h);close()
 }
 drawPath(wave1,brush=Brush.verticalGradient(listOf(accent.copy(alpha=.05f),accent.copy(alpha=.72f),accent.copy(alpha=.10f)),startY=h*.60f,endY=h))
 val wave2=Path().apply{
 moveTo(0f,h*.89f)
 cubicTo(w*.28f,h*.82f,w*.41f,h*1.04f,w*.66f,h*.91f)
 cubicTo(w*.86f,h*.82f,w*.92f,h*.87f,w,h*.83f)
 lineTo(w,h);lineTo(0f,h);close()
 }
 drawPath(wave2,brush=Brush.verticalGradient(listOf(accent.copy(alpha=.04f),accent.copy(alpha=.42f),background.copy(alpha=.86f)),startY=h*.82f,endY=h))
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
 }else Image(bitmap=wallpaper,contentDescription="Fondo personalizado",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
 Column(Modifier.fillMaxSize().background(if(wallpaper!=null)Color.Black.copy(alpha=wallpaperDim) else Color.Transparent).statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp, vertical=12.dp)){
 Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
 Image(painter=painterResource(id=R.drawable.apuntalo_launcher_logo),contentDescription="Logo APÚNTALO",modifier=Modifier.size(60.dp))
 Text("APÚNTALO",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Black,color=foreground)
 Text("que se te olvida",color=accent)
 }
 Text("Creada por Gilmar",style=MaterialTheme.typography.labelSmall,color=foreground)
 Spacer(Modifier.height(12.dp))
 Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
 listOf("☑ Lista","▦ Agenda","◈ Gastos","⚙ Temas").forEachIndexed{idx,name->
 Button(onClick={tab=idx},contentPadding=PaddingValues(horizontal=7.dp),colors=ButtonDefaults.buttonColors(containerColor=if(tab==idx)accent else panel)){Text(name,color=if(tab==idx)Color.Black else Color.White)}
 }
 }
 Spacer(Modifier.height(14.dp))
 when(tab){
 0,1->{
 if(tab==0){
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
 TextButton(onClick={shoppingMode=!shoppingMode}){Text(if(shoppingMode)"Ver tareas" else "🛒 Compras")}
 }
 }
 if(tab==0 && shoppingMode){
 Text("Listas de compras",color=foreground,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 Row(verticalAlignment=Alignment.CenterVertically){
 OutlinedTextField(shoppingEntry,{shoppingEntry=it},modifier=Modifier.weight(1f),label={Text("Añadir producto")},singleLine=true)
 Spacer(Modifier.width(6.dp))
 Button(onClick={if(shoppingEntry.isNotBlank()){shopping.put(JSONObject().put("id",System.currentTimeMillis()).put("title",shoppingEntry.trim()).put("done",false));shoppingSave(shopping);shoppingEntry=""}}){Text("+")}
 }
 LazyColumn{items((0 until shopping.length()).map{shopping.getJSONObject(it)},key={it.optLong("id")}){item->
 Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
 Checkbox(checked=item.optBoolean("done"),onCheckedChange={item.put("done",it);shoppingSave(shopping)})
 Text(item.optString("title"),modifier=Modifier.weight(1f),color=foreground)
 TextButton(onClick={shoppingSave(remove(shopping,item.optLong("id")))}){Text("Borrar")}
 }
 }}
 }else{
 if(tab==1){
 val cal=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,monthOffset)}
 val month=SimpleDateFormat("MMMM yyyy",Locale("es","PR")).format(cal.time).replaceFirstChar{it.uppercase()}
 val offset=(cal.get(Calendar.DAY_OF_WEEK)+5)%7
 val days=cal.getActualMaximum(Calendar.DAY_OF_MONTH)
 Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=panel)){
 Column(Modifier.padding(14.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={monthOffset--;selectedDay=1}){Text("‹",style=MaterialTheme.typography.headlineSmall)};Text(month,modifier=Modifier.weight(1f),color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);TextButton(onClick={monthOffset++;selectedDay=1}){Text("›",style=MaterialTheme.typography.headlineSmall)}}
 Row {listOf("L","M","X","J","V","S","D").forEach{Text(it,modifier=Modifier.weight(1f),color=accent,style=MaterialTheme.typography.labelMedium)}}
 for(week in 0 until ((offset+days+6)/7)){
 Row {for(col in 0..6){val day=week*7+col-offset+1
 if(day in 1..days){
 val hasTask=(0 until tasks.length()).any{idx->val time=tasks.getJSONObject(idx).optLong("time");if(time<=0)false else Calendar.getInstance().apply{timeInMillis=time}.let{it.get(Calendar.YEAR)==cal.get(Calendar.YEAR)&&it.get(Calendar.MONTH)==cal.get(Calendar.MONTH)&&it.get(Calendar.DAY_OF_MONTH)==day}}
 Box(Modifier.weight(1f).padding(2.dp).background(if(selectedDay==day)accent else if(hasTask)accent.copy(alpha=0.2f) else Color.Transparent,RoundedCornerShape(10.dp)).clickable{selectedDay=day}.height(43.dp),contentAlignment=Alignment.Center){Text(if(hasTask)"$day •" else "$day",color=if(selectedDay==day)Color.Black else Color.White)}
 }else Box(Modifier.weight(1f).height(43.dp)){}
 }}
 }
 Spacer(Modifier.height(8.dp))
 Text("Día $selectedDay · Toca un día para consultar sus tareas",color=Color.White.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 }
 }
 }

 Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=panel)){Column(Modifier.padding(18.dp)){Text("TU PRODUCTIVIDAD",color=accent,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold);Spacer(Modifier.height(7.dp));val done=(0 until tasks.length()).count{tasks.getJSONObject(it).optBoolean("done")};Text("$done de ${tasks.length()} completadas",color=Color.White,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={if(tasks.length()==0)0f else done.toFloat()/tasks.length()},modifier=Modifier.fillMaxWidth().height(7.dp),color=accent,trackColor=Color.White.copy(alpha=0.12f))}};Row {Text(if(tab==0)"Mis tareas" else "Próximos recordatorios",color=foreground,modifier=Modifier.weight(1f));Button(onClick={title="";whenText="";editing=0;add=true}){Text("+ Tarea")}}
 val sorted=(0 until tasks.length()).map{tasks.getJSONObject(it)}.filter{tab==0|| (it.optLong("time")>0 && Calendar.getInstance().apply{timeInMillis=it.optLong("time")}.let{d->val m=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,monthOffset)};d.get(Calendar.YEAR)==m.get(Calendar.YEAR)&&d.get(Calendar.MONTH)==m.get(Calendar.MONTH)&&d.get(Calendar.DAY_OF_MONTH)==selectedDay})}.sortedWith(compareBy({it.optBoolean("done")},{if(it.optLong("time")==0L)Long.MAX_VALUE else it.optLong("time")}))
 LazyColumn {items(sorted,key={it.optLong("id")}){o->
  Card(Modifier.fillMaxWidth().padding(vertical=5.dp),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=panel)){
  Row(Modifier.padding(10.dp)){
   Checkbox(checked=o.optBoolean("done"),onCheckedChange={done->
    o.put("done",done);if(done)cancelAlarm(activity,o.optLong("id")) else if(o.optLong("time")>System.currentTimeMillis())notifyAlarm(activity,o.optLong("id"),o.optString("title"),o.optLong("time"));taskSave(tasks)
   })
   Column(Modifier.weight(1f).clickable{editing=o.optLong("id");title=o.optString("title");whenText=if(o.optLong("time")>0)fmt(o.optLong("time")) else "";add=true}){
    Text(o.optString("title"),color=Color.White,fontWeight=FontWeight.SemiBold)
    if(o.optLong("time")>0)Text(fmt(o.optLong("time")),color=teal,style=MaterialTheme.typography.bodySmall)
   }
   TextButton(onClick={shareTask(activity,o.optString("title"),o.optLong("time"))},contentPadding=PaddingValues(horizontal=4.dp)){Text("Compartir",color=accent)}
   TextButton(onClick={cancelAlarm(activity,o.optLong("id"));taskSave(remove(tasks,o.optLong("id")))}){Text("Borrar")}
  }}
 }}
 }
 }
 2->{
 Text("Resumen financiero · "+SimpleDateFormat("MMMM yyyy",Locale("es","PR")).format(Date()),color=foreground,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 OutlinedTextField(value=income,onValueChange={income=it;p.edit().putString("income_"+currentMonth,it).putString("income_default",it).apply()},label={Text("Ingreso mensual (ej. 2500.00)")},singleLine=true)
 val total=(0 until expenses.length()).sumOf{expenses.getJSONObject(it).optDouble("amount")}
 Text("Gastos: $%.2f".format(total),color=foreground)
 Card(Modifier.fillMaxWidth().padding(vertical=12.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=panel)){Column(Modifier.padding(20.dp)){Text("BALANCE DISPONIBLE",color=accent,style=MaterialTheme.typography.labelMedium);Text("$%.2f".format((income.toDoubleOrNull()?:0.0)-total),color=Color.White,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Black);Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={if((income.toDoubleOrNull()?:0.0)<=0)0f else (total/(income.toDoubleOrNull()?:1.0)).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(7.dp),color=accent,trackColor=Color.White.copy(alpha=0.12f))}}
 val categories=listOf("Hogar","Comida","Transporte","Familia","Salud","Otros")
 val chartColors=listOf(Color(0xFF62C4B4),Color(0xFFFFB86B),Color(0xFF8DA8FF),Color(0xFFCE8DF4),Color(0xFFFF7F91),Color(0xFFADB6C5))
 val sums=categories.map{category->(0 until expenses.length()).sumOf{idx->val e=expenses.getJSONObject(idx);if(e.optString("category","Otros")==category)e.optDouble("amount").coerceAtLeast(0.0) else 0.0}}
 Card(Modifier.fillMaxWidth().padding(vertical=10.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=panel)){
 Column(Modifier.padding(18.dp)){
 Text("DISTRIBUCIÓN DE GASTOS",color=accent,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
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
 Text("Gastado",color=Color.White.copy(alpha=0.7f),style=MaterialTheme.typography.labelMedium)
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
 if(total<=0.0)Text("Registra un gasto para ver tu gráfica.",color=Color.White.copy(alpha=0.7f))
 }
 }
 Card(Modifier.fillMaxWidth().padding(vertical=10.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=panel)){
 Column(Modifier.padding(16.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){
 Text("PRESUPUESTOS",color=accent,modifier=Modifier.weight(1f),fontWeight=FontWeight.Bold)
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
 Button(onClick={title="";amount="";expenseCategory="Otros";editing=0;add=true}){Text("+ Gasto")}
 LazyColumn {items((0 until expenses.length()).map{expenses.getJSONObject(it)}){o->
 Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween){
 Column(Modifier.weight(1f).clickable{editing=o.optLong("id");title=o.optString("title");amount=o.optDouble("amount").toString();expenseCategory=o.optString("category","Otros");add=true}){Text(o.optString("title"),color=foreground);Text("$%.2f".format(o.optDouble("amount")),color=accent)}
 TextButton(onClick={expenseSave(remove(allExpenses,o.optLong("id")) )}){Text("Borrar")}
 }
 }}
 }
 else->{
 Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
 Column(Modifier.weight(1f)){
 Text("Notificaciones",color=foreground,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
 Text("Recordatorios de tareas con fecha y hora",color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
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
 HorizontalDivider()
 Text("Wallpaper personalizado",color=foreground,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
 Text("Usa una foto de tu galería como fondo de la aplicación",color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
 Button(onClick={activity.chooseWallpaper()}){Text("⬆ Subir wallpaper")}
 if(wallpaper!=null)OutlinedButton(onClick={activity.removeWallpaper()}){Text("Quitar fondo")}
 }
 HorizontalDivider()
 if(wallpaper!=null){
 Text("Oscurecer wallpaper: ${(wallpaperDim*100).toInt()} %",color=foreground)
 Slider(value=wallpaperDim,onValueChange={wallpaperDim=it;p.edit().putFloat("wallpaper_dim",it).apply()},valueRange=0f..1f)
 }
 Text("Tema visual",color=foreground)
 Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
 Column(Modifier.weight(1f)){
 Text("Tema aleatorio",color=foreground,style=MaterialTheme.typography.titleMedium)
 Text("Un tema diferente al abrir la app",color=foreground.copy(alpha=0.7f),style=MaterialTheme.typography.bodySmall)
 }
 Switch(checked=randomTheme,onCheckedChange={enabled->
 randomTheme=enabled
 p.edit().putBoolean("random_theme",enabled).apply()
 if(enabled){theme=themes.filter{it!=theme}.random();p.edit().putString("theme",theme).apply()}
 })
 }
 LazyColumn {items(themes){name->Row(Modifier.fillMaxWidth().clickable{randomTheme=false;theme=name;p.edit().putBoolean("random_theme",false).putString("theme",name).apply()}.padding(9.dp)){Text(if(theme==name && !randomTheme)"✓ $name" else name,color=foreground)}}}
 }
 }
 }
 }
 }
 if(add)AlertDialog(onDismissRequest={add=false},title={Text(if(tab==2)"Gasto" else "Tarea")},text={
 Column {
 OutlinedTextField(title,{title=it},label={Text("Descripción")})
 if(tab==2){
 OutlinedTextField(amount,{amount=it},label={Text("Cantidad")})
 Text("Categoría",style=MaterialTheme.typography.labelMedium)
 val cats=listOf("Hogar","Comida","Transporte","Familia","Salud","Otros")
 cats.chunked(3).forEach{group->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
 group.forEach{cat->FilterChip(selected=expenseCategory==cat,onClick={expenseCategory=cat},label={Text(cat,style=MaterialTheme.typography.labelSmall)})}
 }}
 }
 else {
 OutlinedTextField(whenText,{whenText=it},label={Text("Fecha y hora (dd/MM/yyyy HH:mm)")})
 Text("Deja vacío para una tarea sin alarma",style=MaterialTheme.typography.bodySmall)
 }
 }
 },confirmButton={TextButton(onClick={
 if(title.isNotBlank()){
 val id=if(editing!=0L)editing else System.currentTimeMillis()
 if(tab==2){
 val a=if(editing!=0L)remove(allExpenses,id)else allExpenses
 a.put(JSONObject().put("id",id).put("title",title).put("amount",amount.toDoubleOrNull()?:0.0).put("category",expenseCategory).put("month",currentMonth))
 expenseSave(a)
 }else{
 val t=if(whenText.isBlank())0L else parse(whenText)?:-1L
 if(t>=0){
 val old=(0 until tasks.length()).firstOrNull{tasks.getJSONObject(it).optLong("id")==id}?.let{tasks.getJSONObject(it)}
 val a=if(editing!=0L)remove(tasks,id)else tasks
 a.put(JSONObject().put("id",id).put("title",title).put("time",t).put("done",old?.optBoolean("done")?:false))
 cancelAlarm(activity,id)
 if(t>System.currentTimeMillis()&&old?.optBoolean("done")!=true)notifyAlarm(activity,id,title,t)
 taskSave(a)
 }
 }
 add=false
 }
 }){Text("Guardar")}},dismissButton={TextButton(onClick={add=false}){Text("Cancelar")}})
}

class TaskWidgetProvider:AppWidgetProvider(){
 override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){
  val tasks=read(context.getSharedPreferences("apuntalo_alpha",0),"tasks")
  val pending=(0 until tasks.length()).map{tasks.getJSONObject(it)}.filter{!it.optBoolean("done")}.sortedBy{if(it.optLong("time")>0)it.optLong("time")else Long.MAX_VALUE}
  val lines=if(pending.isEmpty())"Todo al día ✓" else pending.take(4).joinToString("\n"){ "• "+it.optString("title") }
  ids.forEach{id->
   val view=RemoteViews(context.packageName,com.gilmar.apuntalo.R.layout.apuntalo_widget)
   view.setTextViewText(com.gilmar.apuntalo.R.id.widget_tasks,lines)
   view.setTextViewText(com.gilmar.apuntalo.R.id.widget_header,"APÚNTALO · "+pending.size+" pendientes")
   val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   view.setOnClickPendingIntent(com.gilmar.apuntalo.R.id.widget_root,open)
   manager.updateAppWidget(id,view)
  }
 }
 override fun onReceive(context:Context,intent:Intent){
  super.onReceive(context,intent)
  if(intent.action=="com.gilmar.apuntalo.REFRESH_WIDGET"){
   val mgr=AppWidgetManager.getInstance(context)
   onUpdate(context,mgr,mgr.getAppWidgetIds(android.content.ComponentName(context,TaskWidgetProvider::class.java)))
  }
 }
}
