package app.party.music

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native, immersive controls above the existing MPV surface. No second player. */
internal class MpvFullscreenControls(
    private val act: Activity,
    private val player: MpvVideoPlayer,
    private val send: (String) -> Unit,
    private val exit: () -> Unit,
    private val sourceTitle: () -> String,
    private val isYoutube: () -> Boolean,
    private val isAudio: () -> Boolean,
    private val quality: () -> Int,
    private val qualities: () -> List<Int>
) : FrameLayout(act) {
    private val handler=Handler(Looper.getMainLooper())
    private val audio=act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val accent=Color.rgb(202,151,250)
    private val oldBrightness=act.window.attributes.screenBrightness
    private val oldOrientation=act.requestedOrientation
    private val oldFlags=act.window.decorView.systemUiVisibility
    private val oldWindowFlags=act.window.attributes.flags
    private var oldBehavior=0
    private var controlsVisible=true
    private var dragging=false
    private var dialogOpen=false
    private var downX=0f;private var downY=0f;private var startBrightness=.5f;private var startVolume=0
    private var zone=0;private var gesturing=false;private var canGesture=false
    private val art=AlbumCanvas(act)
    private val top=LinearLayout(act)
    private val bottom=LinearLayout(act)
    private val middle=LinearLayout(act)
    private val title=TextView(act)
    private val time=TextView(act)
    private val seek=SeekBar(act)
    private val play=Button(act)
    private val tracks=Button(act)
    private val aspect=Button(act)
    private val q=Button(act)
    private val hud=TextView(act)
    private val hideTask=Runnable { if(!player.isPaused()&&!dragging&&!dialogOpen)showControls(false) }

    init {
        isClickable=true
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(art,LayoutParams(-1,-1))
        top.orientation=LinearLayout.HORIZONTAL;top.gravity=Gravity.CENTER_VERTICAL
        top.setPadding(dp(18),dp(8),dp(18),dp(8))
        top.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xcc0d0919.toInt(),0x000d0919))
        addView(top,LayoutParams(-1,dp(70),Gravity.TOP))
        top.addView(button("‹", "Exit fullscreen") { exit() },LinearLayout.LayoutParams(dp(54),dp(48)))
        val heading=LinearLayout(act);heading.orientation=LinearLayout.VERTICAL;heading.setPadding(dp(12),0,dp(8),0)
        val brand=TextView(act);brand.text="WATCH PARTY  /  MPV";brand.textSize=10f;brand.setTextColor(accent);brand.letterSpacing=.12f
        title.textSize=17f;title.setTextColor(Color.WHITE);title.maxLines=1;title.ellipsize=TextUtils.TruncateAt.END
        heading.addView(brand);heading.addView(title);top.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        middle.orientation=LinearLayout.HORIZONTAL;middle.gravity=Gravity.CENTER
        middle.background=rounded(0x8820152d.toInt(),40f)
        middle.setPadding(dp(8),dp(4),dp(8),dp(4))
        middle.addView(button("↶ 10","Back ten seconds") { send("seekrel:-10");wake() },LinearLayout.LayoutParams(dp(76),dp(64)))
        styleButton(play,"▶");play.textSize=28f;play.contentDescription="Play or pause";play.setOnClickListener{send("toggle");wake()}
        middle.addView(play,LinearLayout.LayoutParams(dp(78),dp(68)))
        middle.addView(button("10 ↷","Forward ten seconds") { send("seekrel:10");wake() },LinearLayout.LayoutParams(dp(76),dp(64)))
        addView(middle,LayoutParams(-2,-2,Gravity.CENTER))
        bottom.orientation=LinearLayout.VERTICAL;bottom.setPadding(dp(26),dp(12),dp(26),dp(12))
        bottom.background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xe6100b1c.toInt(),0x00100b1c))
        addView(bottom,LayoutParams(-1,dp(110),Gravity.BOTTOM))
        seek.max=10000;seek.progressTintList=ColorStateList.valueOf(accent);seek.thumbTintList=ColorStateList.valueOf(accent);seek.contentDescription="Playback position"
        bottom.addView(seek,LinearLayout.LayoutParams(-1,dp(35)))
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(v:SeekBar){dragging=true;handler.removeCallbacks(hideTask)}
            override fun onProgressChanged(v:SeekBar,value:Int,user:Boolean){if(user)time.text="${clock(player.duration()*value/10000)} / ${clock(player.duration())}"}
            override fun onStopTrackingTouch(v:SeekBar){dragging=false;if(player.duration()>0)send("seekabs:${player.duration()*v.progress/10000}");wake()}
        })
        val row=LinearLayout(act);row.gravity=Gravity.CENTER_VERTICAL
        time.textSize=12f;time.setTextColor(Color.WHITE);row.addView(time,LinearLayout.LayoutParams(0,-2,1f))
        styleButton(aspect,"Aspect");aspect.contentDescription="Video aspect ratio";aspect.setOnClickListener{chooseAspect()};row.addView(aspect,LinearLayout.LayoutParams(dp(100),dp(42)))
        styleButton(tracks,"Audio");tracks.setOnClickListener{chooseAudio()};row.addView(tracks,LinearLayout.LayoutParams(dp(110),dp(42)))
        styleButton(q,"144p");q.setOnClickListener{chooseQuality()};row.addView(q,LinearLayout.LayoutParams(dp(85),dp(42)))
        bottom.addView(row)
        hud.textSize=18f;hud.setTextColor(Color.WHITE);hud.gravity=Gravity.CENTER;hud.setPadding(dp(24),dp(15),dp(24),dp(15));hud.background=rounded(0xdd241a34.toInt(),18f);hud.visibility=GONE
        addView(hud,LayoutParams(-2,-2,Gravity.CENTER))
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if(Build.VERSION.SDK_INT>=30)oldBehavior=act.window.insetsController?.systemBarsBehavior?:0
        act.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        immerse();tick();wake()
    }
    private fun rounded(color:Int,radius:Float)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius.toInt()).toFloat()}
    private fun styleButton(b:Button,label:String){b.text=label;b.isAllCaps=false;b.textSize=13f;b.setTextColor(Color.WHITE);b.background=rounded(0x332d2140,14f);b.setPadding(dp(6),0,dp(6),0)}
    private fun button(label:String,description:String,action:()->Unit)=Button(act).apply{styleButton(this,label);contentDescription=description;setOnClickListener{action()}}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
    private fun clock(t:Double):String{val n=t.coerceAtLeast(0.0).toInt();return if(n>=3600)"${n/3600}:${(n/60%60).toString().padStart(2,'0')}:${(n%60).toString().padStart(2,'0')}" else "${n/60}:${(n%60).toString().padStart(2,'0')}"}
    fun immerse(){
        if(Build.VERSION.SDK_INT>=30){act.window.insetsController?.apply{systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsets.Type.systemBars())}}
        else act.window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    fun tick(){
        title.text=player.title().ifBlank{sourceTitle()}
        play.text=if(player.isPaused()||player.ended())"▶" else "❚❚"
        if(!dragging){time.text="${clock(player.position())} / ${clock(player.duration())}";seek.progress=if(player.duration()>0)(player.position()/player.duration()*10000).toInt().coerceIn(0,10000) else 0}
        seek.isEnabled=player.duration()>0
        val count=player.audioTracks().size;tracks.text=if(count>1)"Audio · $count" else "Audio";tracks.isEnabled=count>0;tracks.alpha=if(count>0)1f else .4f
        aspect.visibility=if(isAudio())GONE else VISIBLE
        q.visibility=if(isYoutube())VISIBLE else GONE;q.text="${quality()}p"
        art.visibility=if(isAudio()&&!player.hasArtwork())VISIBLE else GONE
        if(player.isPaused()&&!controlsVisible)wake()
    }
    private fun chooseAudio(){
        val available=player.audioTracks();if(available.isEmpty())return
        if(available.size==1){Toast.makeText(act,"Is file mein sirf ek audio track hai",Toast.LENGTH_SHORT).show();wake();return}
        dialogOpen=true;wake()
        val labels=available.mapIndexed{i,t->"${i+1}. ${t.label}"}.toTypedArray()
        val dialog=AlertDialog.Builder(act).setTitle("Audio language / track")
            .setSingleChoiceItems(labels,available.indexOfFirst{it.selected}){d,index->
                player.selectAudio(available[index].id){ok->Toast.makeText(act,if(ok)"Audio: ${available[index].label}" else "Audio switch confirm nahi hua",Toast.LENGTH_SHORT).show();tick()};d.dismiss()
            }.setNegativeButton("Close",null).create()
        dialog.setOnDismissListener{dialogOpen=false;immerse();wake()};dialog.show()
    }
    private fun chooseAspect(){
        dialogOpen=true;wake()
        val dialog=AlertDialog.Builder(act).setTitle("Aspect ratio · this phone only")
            .setSingleChoiceItems(player.aspectLabels.toTypedArray(),player.aspectIndex){d,i->
                val ok=player.setAspect(i)
                Toast.makeText(act,if(ok)player.aspectLabels[i] else "Aspect change failed",Toast.LENGTH_SHORT).show()
                d.dismiss()
            }.setNegativeButton("Close",null).create()
        dialog.setOnDismissListener{dialogOpen=false;immerse();wake()};dialog.show()
    }
    private fun chooseQuality(){
        val qs=qualities();if(qs.isEmpty())return
        dialogOpen=true;wake()
        val dialog=AlertDialog.Builder(act).setTitle("Video quality · manual")
            .setSingleChoiceItems(qs.map{"${it}p"}.toTypedArray(),qs.indexOf(quality())){d,i->send("quality:${qs[i]}");d.dismiss()}.setNegativeButton("Close",null).create()
        dialog.setOnDismissListener{dialogOpen=false;immerse();wake()};dialog.show()
    }
    private fun showControls(show:Boolean){controlsVisible=show;val visible=if(show)VISIBLE else INVISIBLE;top.visibility=visible;bottom.visibility=visible;middle.visibility=visible}
    private fun wake(){showControls(true);handler.removeCallbacks(hideTask);handler.postDelayed(hideTask,3200)}
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
        if(e.actionMasked==MotionEvent.ACTION_DOWN){
            downX=e.x;downY=e.y;zone=if(e.x<width/2f)1 else 2;gesturing=false
            val rect=Rect();val blocked=listOf(top,bottom,middle).any{it.visibility==VISIBLE&&run{it.getHitRect(rect);rect.contains(e.x.toInt(),e.y.toInt())}}
            canGesture=!blocked
            startBrightness=act.window.attributes.screenBrightness.takeIf{it>=0}?:run{try{Settings.System.getInt(act.contentResolver,Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}}
            startVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        if(e.actionMasked==MotionEvent.ACTION_MOVE&&canGesture&&abs(e.y-downY)>dp(12)&&abs(e.y-downY)>abs(e.x-downX)*1.3f){gesturing=true;return true}
        return false
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_MOVE->{
                // Blank-area DOWN is handled by this ViewGroup itself; Android may
                // skip onInterceptTouchEvent for later MOVE events in that case.
                if(canGesture && abs(e.y-downY)>dp(12) && abs(e.y-downY)>abs(e.x-downX)*1.3f)gesturing=true
                if(gesturing){
                val change=(downY-e.y)/height.coerceAtLeast(1)*1.5f
                if(zone==1){val value=(startBrightness+change).coerceIn(.03f,1f);act.window.attributes=act.window.attributes.apply{screenBrightness=value};hud.text="☀  Brightness ${(value*100).roundToInt()}%"}
                else{val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);val value=(startVolume+change*max).roundToInt().coerceIn(0,max);try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0)}catch(_:SecurityException){};hud.text="♪  Volume ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} / $max"}
                hud.visibility=VISIBLE;handler.removeCallbacks(hideTask)
                }
            }
            MotionEvent.ACTION_UP->{if(gesturing){hud.visibility=GONE;wake()}else if(controlsVisible)showControls(false)else wake();gesturing=false;performClick()}
            MotionEvent.ACTION_CANCEL->{gesturing=false;hud.visibility=GONE;wake()}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    fun release(){
        handler.removeCallbacksAndMessages(null)
        act.window.attributes=act.window.attributes.apply{screenBrightness=oldBrightness}
        act.requestedOrientation=oldOrientation
        act.window.setFlags(oldWindowFlags,WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(Build.VERSION.SDK_INT>=30)act.window.insetsController?.apply{systemBarsBehavior=oldBehavior;show(WindowInsets.Type.systemBars())}
        act.window.decorView.systemUiVisibility=oldFlags
    }
    private class AlbumCanvas(c:Context):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            val w=width.toFloat();val h=height.toFloat();p.shader=LinearGradient(0f,0f,w,h,intArrayOf(0xff412151.toInt(),0xff151124.toInt(),0xff192947.toInt()),null,Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,h,p);p.shader=null
            val r=minOf(w,h)*.26f;val x=w/2;val y=h/2;p.color=0xff120e1c.toInt();canvas.drawCircle(x,y,r,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=0xff45324e.toInt();for(i in 1..20)canvas.drawCircle(x,y,r*i/20,p);p.style=Paint.Style.FILL
            p.shader=LinearGradient(x-r,y-r,x+r,y+r,0xffb788ef.toInt(),0xffffb9ad.toInt(),Shader.TileMode.CLAMP);canvas.drawCircle(x,y,r*.42f,p);p.shader=null
            p.color=0xff291a3f.toInt();p.textSize=r*.52f;p.textAlign=Paint.Align.CENTER;canvas.drawText("♫",x,y+r*.18f,p)
            p.textSize=12*resources.displayMetrics.scaledDensity;p.color=0xffdbcbef.toInt();canvas.drawText("YOUR MUSIC. YOUR MOMENT.",x,y+r+32*resources.displayMetrics.density,p)
        }
    }
}
