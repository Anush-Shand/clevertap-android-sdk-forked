package com.clevertap.demo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clevertap.android.sdk.CleverTapAPI
import com.clevertap.android.sdk.variables.CTVariables
import com.clevertap.android.sdk.variables.Var
import com.clevertap.android.sdk.variables.callbacks.VariableCallback
import com.clevertap.android.sdk.variables.callbacks.VariablesChangedCallback
import com.clevertap.demo.databinding.ActivityTestBinding
import com.clevertap.demo.variables_test.VarActivity1
import java.util.*
import kotlin.concurrent.thread

fun Activity.log(str:String,throwable: Throwable?=null){
    Log.e("UI||",this::class.java.name.toString()+"||"+ str, throwable)
}

@SuppressLint("SetTextI18n")
class TestActivity : AppCompatActivity() {
    companion object{
        fun Activity.toast(str:String){
            Toast.makeText(this, str, Toast.LENGTH_LONG).show()
            log(str)
        }


        fun TextView.flash(content:String?=null) {
            if(content!=null) this.text = content
            setBackgroundColor(Color.YELLOW)
            thread {
                Thread.sleep(300)
                (context as? Activity)?.runOnUiThread { setBackgroundColor(Color.WHITE) }
            }
        }
    }

    private var ctApi: CleverTapAPI? = null
    private val binding: ActivityTestBinding by lazy { ActivityTestBinding.inflate(layoutInflater) }

    private val javaInstance = TestMyVarsJavaInstance()
    private var toggle= false

    private var definedVar:Var<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        log("onCreate called")
        ctApi = CleverTapAPI.getDefaultInstance(this)

//        defineVariables(ctApi!!).also { binding.btDefineVars.isEnabled=false }
//        parseVariables(ctApi!!).also { binding.btParse.isEnabled=false }
//        attachListeners(ctApi!!).also { binding.btAttachListeners.isEnabled=false }
//        binding.btAppLaunch.isEnabled = false
        checkLocalValues()


        with(binding) {
            btCheckLocal.setOnClickListener{checkLocalValues()}
            btParse.setOnClickListener{parseVariables(ctApi!!)}
            btDefineVars.setOnClickListener{defineVariables(ctApi!!) }
            btAttachListeners.setOnClickListener { attachListeners(ctApi!!) }
            btAppLaunch.setOnClickListener { appLaunchRequest(ctApi!!) }
            btRequestWzrkFetch.setOnClickListener { wzrkFetchRequestActual(ctApi!!) }
            btServerReqFail.setOnClickListener { serverDataRequestFail(ctApi!!) }
            btSync.setOnClickListener { sync(ctApi!!) }
            btMultiActivity.setOnClickListener {
                startActivity(Intent(this@TestActivity,VarActivity1::class.java))
            }

        }
    }

    override fun onResume() {
        super.onResume()
        //appLaunchRequest(ctApi!!)
    }
    private fun checkLocalValues() {
        binding.tvTerminalValueOnDemand.text = "definedVars="+getDefinedVarsStr()+"\n========\n"+getParsedVarsString()
        binding.tvTerminalValueOnDemand.flash()
    }

    private fun parseVariables(cleverTapAPI: CleverTapAPI){
        toast("parsing various classes")

        cleverTapAPI.parseVariablesForClasses(TestMyVarsJava::class.java)
        cleverTapAPI.parseVariables(javaInstance)
    }
    private fun defineVariables(cleverTapAPI: CleverTapAPI){
        toast("defining variables")
        definedVar = cleverTapAPI.defineVariable("definedVar","hello")
    }
    private fun attachListeners(cleverTapAPI: CleverTapAPI){
        toast("attaching various listeners")
        cleverTapAPI.addVariablesChangedHandler(object : VariablesChangedCallback() {
            override fun variablesChanged() {
                binding.tvTerminalWithGlobalListenerMultiple.text = ("variablesChanged()\n${getParsedVarsString()}")
                binding.tvTerminalWithGlobalListenerMultiple.flash()
            }
        })
        cleverTapAPI.addOneTimeVariablesChangedHandler(object : VariablesChangedCallback() {
            override fun variablesChanged() {
                binding.tvTerminalWithGlobalListenerOneTime.text = ("variablesChanged()\n${getParsedVarsString()}")
                binding.tvTerminalWithGlobalListenerOneTime.flash()
            }
        })

        log("define var is $definedVar")
        definedVar?.addValueChangedHandler(object :VariableCallback<String>(){
            override fun handle(variable: Var<String>?) {
                binding.tvTerminalWithIndividualListeners.text = (getDefinedVarsStr())
                binding.tvTerminalWithIndividualListeners.flash()
            }

        })
    }


    //wzrk_fetch/ app_launch
    private fun appLaunchRequest(cleverTapAPI: CleverTapAPI){
//        toast("requesting app launch")
//        CTExecutorFactory
//            .executors(cleverTapAPI.tempGetConfig())
//            .postAsyncSafelyTask<Unit>()
//            .execute("ctv_CleverTap#APP_LAUNCH(fake)") {
//                val response = FakeServer.getJson(1, this)
//                cleverTapAPI.tempGetVariablesApi().handleVariableResponse(response, null)
//            }

    }

//    private fun wzrkFetchRequest(cleverTapAPI: CleverTapAPI ){
//        toast("requesting wzrk fetch")
//        CTExecutorFactory
//            .executors(cleverTapAPI.tempGetConfig())
//            .postAsyncSafelyTask<Unit>()
//            .execute("ctv_CleverTap#WZRK_FETCH(fake)") {
//                val response =  FakeServer.getJson(if(toggle)1 else 2,this)
//                cleverTapAPI.tempGetVariablesApi().handleVariableResponse(response){ log("handleVariableResponse:$it") }
//                toggle!=toggle
//            }
//    }
    private fun wzrkFetchRequestActual(cleverTapAPI: CleverTapAPI ){
        toast("requesting wzrk fetch")
        cleverTapAPI.wzrkFetchVariables { log("wzrkFetchVariablesActual:$it") }
        toggle!=toggle
    }


    //app launch fail
    private fun serverDataRequestFail(cleverTapAPI: CleverTapAPI){
        toast("requesting app launch(failed)")
        //cleverTapAPI.tempGetVariablesApi().handleVariableResponse(null) { log("handleVariableResponse:$it") }//todo: test

    }

    private fun sync(cleverTapAPI: CleverTapAPI){
        log("sync(pushVariablesToServer)")
        cleverTapAPI.pushVariablesToServer()
    }


    private fun getParsedVarsString():String {
        val ctApi = ctApi?:return  ""
        return StringBuilder().run {
            appendLine("isDevelopmentMode:${CTVariables.isInDevelopmentMode()} |checked on : ${Date()} " )

            appendLine("-------------------------------------------------------------------")

            appendLine("JavaStatic:")
            appendy("- welcomeMsg = ${TestMyVarsJava.welcomeMsg} | var:${ctApi.getVariable<String>("welcomeMsg")}")
            appendy("- isOptedForOffers = ${TestMyVarsJava.isOptedForOffers}| var:${ctApi.getVariable<Boolean>("isOptedForOffers")}")
            appendy("- initialCoins = ${TestMyVarsJava.initialCoins} | var:${ctApi.getVariable<Int>("initialCoins")}")
            appendy("- correctGuessPercentage = ${TestMyVarsJava.correctGuessPercentage}  | var:${ctApi.getVariable<Float>("correctGuessPercentage")}")
            appendy("- userConfigurableProps = ${TestMyVarsJava.userConfigurableProps}  | var:${ctApi.getVariable<HashMap<String, Any>>("userConfigurableProps")}")
            appendy("- samsungS22 = ${TestMyVarsJava.samsungS22}  | var:${ctApi.getVariable<Double>("android.samsung.s22")}")
            appendy("- samsungS23 = ${TestMyVarsJava.samsungS23}  | var:${ctApi.getVariable<String>("android.samsung.s23")}")
            appendy("- nokia12 = ${TestMyVarsJava.nokia12}  | var:${ctApi.getVariable<String>("android.nokia.12")}")
            appendy("- nokia6a = ${TestMyVarsJava.nokia6a}  | var:${ctApi.getVariable<Double>("android.nokia.6a")}")
            appendy("- appleI15 = ${TestMyVarsJava.appleI15}  | var:${ctApi.getVariable<String>("apple.iphone15")}")

            appendLine("-------------------------------------------------------------------")
            appendLine("JavaDynamic:")
            appendy("- javaIStr = ${javaInstance.javaIStr} | var:${ctApi.getVariable<String>("javaIStr")}")
            appendy("- javaIBool = ${javaInstance.javaIBool}| var:${ctApi.getVariable<Boolean>("javaIBool")}")
            appendy("- javaIInt = ${javaInstance.javaIInt} | var:${ctApi.getVariable<Int>("javaIInt")}")
            appendy("- javaIDouble = ${javaInstance.javaIDouble}  | var:${ctApi.getVariable<Double>("javaIDouble")}")

            this.toString()
        }
    }


    private fun getDefinedVarsStr(): String {
        return definedVar.toString()
    }

    fun StringBuilder.appendy(value: String?): StringBuilder = append(value).appendLine().appendLine()


}
