package com.peng.demo.mvc.controller;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.peng.demo.service.IDemoService;
import com.peng.mvcframework.annotation.PDAutowired;
import com.peng.mvcframework.annotation.PDController;
import com.peng.mvcframework.annotation.PDRequestMapping;
import com.peng.mvcframework.annotation.PDRequestParam;

@PDController
@PDRequestMapping("/demo")
public class DemoController {

  	@PDAutowired private IDemoService demoService;

	@PDRequestMapping("/query")
	public void query(HttpServletRequest req, HttpServletResponse resp,
					  @PDRequestParam("name") String name){
		String result = demoService.get(name);
		try {
			resp.getWriter().write(result);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@PDRequestMapping("/add")
	public void add(HttpServletRequest req, HttpServletResponse resp,
					@PDRequestParam("a") Integer a, @PDRequestParam("b") Integer b){
		try {
			resp.getWriter().write(a + "+" + b + "=" + (a + b));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
