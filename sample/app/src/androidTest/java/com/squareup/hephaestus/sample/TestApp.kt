package com.squareup.hephaestus.sample

import android.app.Application
import com.squareup.scopes.ComponentHolder

class TestApp : Application() {

  override fun onCreate() {
    super.onCreate()

    ComponentHolder.components += DaggerTestAppComponent.create()
  }
}
