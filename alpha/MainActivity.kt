package com.gilmar.apuntalo

import android.Manifest
import android.app.*
import android.content.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private val bg=Color(0xFF17191F)
private val card=Color(0xFF252932)
private val teal=Color(0xFF65B9A5)
private const val CHANNEL="apuntalo_tasks"
private fun fmt(t:Long)=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(Date(t))
private fun parse(s:String):Long?=try { SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).apply{isLenient=false}.parse(s)?.time }catch(_:Exception){null}
private fun read(p:android.content.SharedPreferences,k:String):JSONArray=try{JSONArray(p.getString(k,"[]"))}catch(_:Exception){JSONArray()}
private fun save(p:android.content.SharedPreferences,k:String,a:JSONArray){p.edit().putString(k,a.toString()).apply()}
private fun remove(a:JSONArray,id:Long):JSONArray=JSONArray().also{out->for(i in 0 until a.length()){val o=a.getJSONObject(i);if(o.optLong("id")!=id)out.put(o)}}
private fun notifyAlarm(ctx:Context,id:Long,title:String,time:Long){
    val intent=Intent(ctx,ReminderReceiver::class.java).putExtra("title",title).putExtra("id",id)
    val pi=PendingIntent.getBroadcast(ctx,(id%Int.MAX_VALUE).toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val am=ctx.getSystemService(AlarmManager::class.java)
    if(Build.VERSION.SDK_INT>=31 && am.canScheduleExactAlarms())am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pi)
    else if(Build.VERSION.SDK_INT<31)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pi)
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pi)
}
private fun cancelAlarm(ctx:Context,id:Long){
    val pi=PendingIntent.getBroadcast(ctx,(id%Int.MAX_VALUE).toInt(),Intent(ctx,ReminderReceiver::class.java),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    if(pi!=null)ctx.getSystemService(AlarmManager::class.java).cancel(pi)
}
class ReminderReceiver:android.content.BroadcastReceiver(){
 override fun onReceive(c:Context,i:Intent){
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
 override fun onCreate(savedInstanceState:Bundle?){
 super.onCreate(savedInstanceState)
 getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Recordatorios de tareas",NotificationManager.IMPORTANCE_HIGH))
 setContent { Screen(this) }
 }
}
@Composable fun Screen(activity:MainActivity){
 val p=remember{activity.getSharedPreferences("apuntalo_alpha",0)}
 var tick by remember{mutableIntStateOf(0)}
 val tasks=remember(tick){read(p,"tasks")}
 val expenses=remember(tick){read(p,"expenses")}
 var tab by remember{mutableIntStateOf(0)}
 var monthOffset by remember{mutableIntStateOf(0)}
 var selectedDay by remember{mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH))}
 var add by remember{mutableStateOf(false)}
 var title by remember{mutableStateOf("")}
 var whenText by remember{mutableStateOf("")}
 var amount by remember{mutableStateOf("")}
 var income by remember{mutableStateOf(p.getString("income","4700")?:"4700")}
 var editing by remember{mutableLongStateOf(0)}
 var theme by remember{mutableStateOf(p.getString("theme","Midnight")?:"Midnight")}
 val themes=listOf("Midnight","OLED Black","Ocean","Emerald","Purple Haze","Burgundy","Graphite","Arctic","Pure White","Forest","Sunset","Cyberpunk","Dracula","Military","Crimson")
 val accent=when(theme){"Ocean"->Color(0xFF52A9E3);"Crimson"->Color(0xFFE65A66);"Purple Haze"->Color(0xFFAF89F5);"Emerald"->Color(0xFF4ACF92);else->teal}
 val background=when(theme){"OLED Black"->Color.Black;"Pure White","Arctic"->Color(0xFFE9EDF2);"Ocean"->Color(0xFF10283B);"Forest"->Color(0xFF14291F);else->bg}
 val foreground=if(theme=="Pure White"||theme=="Arctic")Color.Black else Color.White
 fun refresh(){tick++}
 fun taskSave(a:JSONArray){save(p,"tasks",a);refresh()}
 fun expenseSave(a:JSONArray){save(p,"expenses",a);refresh()}
 MaterialTheme(colorScheme=if(foreground==Color.Black)lightColorScheme(primary=accent)else darkColorScheme(primary=accent)){
 Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(background,background,accent.copy(alpha=0.15f)))).padding(18.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(52.dp).background(accent.copy(alpha=0.2f),RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){Text("✎",style=MaterialTheme.typography.headlineLarge,color=accent)};Spacer(Modifier.width(12.dp));Text("APÚNTALO",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Black,color=foreground)}
 Text("que se te olvida",color=accent)
 Text("Creada por Gilmar",style=MaterialTheme.typography.labelSmall,color=foreground)
 Spacer(Modifier.height(12.dp))
 Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
 listOf("☑ Lista","▦ Agenda","◈ Gastos","⚙ Temas").forEachIndexed{idx,name->
 Button(onClick={tab=idx},contentPadding=PaddingValues(horizontal=7.dp),colors=ButtonDefaults.buttonColors(containerColor=if(tab==idx)accent else card)){Text(name,color=if(tab==idx)Color.Black else Color.White)}
 }
 }
 Spacer(Modifier.height(14.dp))
 when(tab){
 0,1->{
 if(tab==1){
 val cal=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,monthOffset)}
 val month=SimpleDateFormat("MMMM yyyy",Locale("es","PR")).format(cal.time).replaceFirstChar{it.uppercase()}
 val offset=(cal.get(Calendar.DAY_OF_WEEK)+5)%7
 val days=cal.getActualMaximum(Calendar.DAY_OF_MONTH)
 Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=card)){
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

 Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(18.dp)){Text("TU PRODUCTIVIDAD",color=accent,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold);Spacer(Modifier.height(7.dp));val done=(0 until tasks.length()).count{tasks.getJSONObject(it).optBoolean("done")};Text("$done de ${tasks.length()} completadas",color=Color.White,style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={if(tasks.length()==0)0f else done.toFloat()/tasks.length()},modifier=Modifier.fillMaxWidth().height(7.dp),color=accent,trackColor=Color.White.copy(alpha=0.12f))}};Row {Text(if(tab==0)"Mis tareas" else "Próximos recordatorios",color=foreground,modifier=Modifier.weight(1f));Button(onClick={title="";whenText="";editing=0;add=true}){Text("+ Tarea")}}
 val sorted=(0 until tasks.length()).map{tasks.getJSONObject(it)}.filter{tab==0|| (it.optLong("time")>0 && Calendar.getInstance().apply{timeInMillis=it.optLong("time")}.let{d->val m=Calendar.getInstance().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,monthOffset)};d.get(Calendar.YEAR)==m.get(Calendar.YEAR)&&d.get(Calendar.MONTH)==m.get(Calendar.MONTH)&&d.get(Calendar.DAY_OF_MONTH)==selectedDay})}.sortedWith(compareBy({it.optBoolean("done")},{if(it.optLong("time")==0L)Long.MAX_VALUE else it.optLong("time")}))
 LazyColumn {items(sorted,key={it.optLong("id")}){o->
  Card(Modifier.fillMaxWidth().padding(vertical=5.dp),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=card)){
  Row(Modifier.padding(10.dp)){
   Checkbox(checked=o.optBoolean("done"),onCheckedChange={done->
    o.put("done",done);if(done)cancelAlarm(activity,o.optLong("id")) else if(o.optLong("time")>System.currentTimeMillis())notifyAlarm(activity,o.optLong("id"),o.optString("title"),o.optLong("time"));taskSave(tasks)
   })
   Column(Modifier.weight(1f).clickable{editing=o.optLong("id");title=o.optString("title");whenText=if(o.optLong("time")>0)fmt(o.optLong("time")) else "";add=true}){
    Text(o.optString("title"),color=Color.White,fontWeight=FontWeight.SemiBold)
    if(o.optLong("time")>0)Text(fmt(o.optLong("time")),color=teal,style=MaterialTheme.typography.bodySmall)
   }
   TextButton(onClick={cancelAlarm(activity,o.optLong("id"));taskSave(remove(tasks,o.optLong("id")))}){Text("Borrar")}
  }}
 }}
 }
 2->{
 Text("Resumen financiero",color=foreground,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
 OutlinedTextField(value=income,onValueChange={income=it;p.edit().putString("income",it).apply()},label={Text("Ingreso mensual")},singleLine=true)
 val total=(0 until expenses.length()).sumOf{expenses.getJSONObject(it).optDouble("amount")}
 Text("Gastos: $%.2f".format(total),color=foreground)
 Card(Modifier.fillMaxWidth().padding(vertical=12.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=card)){Column(Modifier.padding(20.dp)){Text("BALANCE DISPONIBLE",color=accent,style=MaterialTheme.typography.labelMedium);Text("$%.2f".format((income.toDoubleOrNull()?:0.0)-total),color=Color.White,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Black);Spacer(Modifier.height(12.dp));LinearProgressIndicator(progress={if((income.toDoubleOrNull()?:0.0)<=0)0f else (total/(income.toDoubleOrNull()?:1.0)).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(7.dp),color=accent,trackColor=Color.White.copy(alpha=0.12f))}}
 Button(onClick={title="";amount="";editing=0;add=true}){Text("+ Gasto")}
 LazyColumn {items((0 until expenses.length()).map{expenses.getJSONObject(it)}){o->
 Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween){
 Column(Modifier.weight(1f).clickable{editing=o.optLong("id");title=o.optString("title");amount=o.optDouble("amount").toString();add=true}){Text(o.optString("title"),color=foreground);Text("$%.2f".format(o.optDouble("amount")),color=accent)}
 TextButton(onClick={expenseSave(remove(expenses,o.optLong("id")))}){Text("Borrar")}
 }
 }}
 }
 else->{
 Text("Tema visual",color=foreground)
 LazyColumn {items(themes){name->Row(Modifier.fillMaxWidth().clickable{theme=name;p.edit().putString("theme",name).apply()}.padding(9.dp)){Text(if(theme==name)"✓ $name" else name,color=foreground)}}}
 }
 }
 }
 }
 if(add)AlertDialog(onDismissRequest={add=false},title={Text(if(tab==2)"Gasto" else "Tarea")},text={
 Column {
 OutlinedTextField(title,{title=it},label={Text("Descripción")})
 if(tab==2)OutlinedTextField(amount,{amount=it},label={Text("Cantidad")})
 else {
 OutlinedTextField(whenText,{whenText=it},label={Text("Fecha y hora (dd/MM/yyyy HH:mm)")})
 Text("Deja vacío para una tarea sin alarma",style=MaterialTheme.typography.bodySmall)
 }
 }
 },confirmButton={TextButton(onClick={
 if(title.isNotBlank()){
 val id=if(editing!=0L)editing else System.currentTimeMillis()
 if(tab==2){
 val a=if(editing!=0L)remove(expenses,id)else expenses
 a.put(JSONObject().put("id",id).put("title",title).put("amount",amount.toDoubleOrNull()?:0.0))
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
