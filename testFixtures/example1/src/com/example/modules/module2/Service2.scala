package com.example.modules.module2

import com.example.modules.module1.Service1
import org.thirdparty.Ext

class Service2:
  def run(): Unit =
    val s = new Service1
    println(s.run())
    println(Ext.name)
