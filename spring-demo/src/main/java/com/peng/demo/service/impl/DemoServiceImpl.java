package com.peng.demo.service.impl;

import com.peng.demo.service.IDemoService;
import com.peng.mvcframework.annotation.PDService;

/**
 * 核心业务逻辑
 */
@PDService
public class DemoServiceImpl implements IDemoService{

	public String get(String name) {
		return "My name is " + name;
	}
}
