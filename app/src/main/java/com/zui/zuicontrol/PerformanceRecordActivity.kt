package com.zui.zuicontrol

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Retained component identity for internal/deep links; one Owner presentation authority. */
class PerformanceRecordActivity : Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("openRecord",true)
            for(key in listOf("package","thread","name"))putExtra(key,intent.getStringExtra(key).orEmpty())
            putExtra("threads",intent.getBooleanExtra("threads",false) || intent.getStringExtra("thread").orEmpty().isNotEmpty())
        })
        finish()
    }
}
