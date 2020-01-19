package com.peng.mvcframework.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.peng.mvcframework.annotation.PDAutowired;
import com.peng.mvcframework.annotation.PDController;
import com.peng.mvcframework.annotation.PDRequestMapping;
import com.peng.mvcframework.annotation.PDRequestParam;
import com.peng.mvcframework.annotation.PDService;

public class PDDispatcherServlet extends HttpServlet{

    //存储aplication.properties的配置内容
    private Properties contextConfig = new Properties();
    
    //存储所有扫描到的类
    private List<String> classNames = new ArrayList<String>();
    
    //ioc容器
    private Map<String,Object> ioc = new HashMap<String,Object>();
    
    //保存所有的Url和方法的映射关系
    private List<Handler> handlerMapping = new ArrayList<Handler>();
    
    @Override
    public void init(ServletConfig config) throws ServletException {
        //1、加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        //2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
        //3、初始化所有相关的类的实例，并且放入到IOC容器之中
        doInstance();
        //4、完成依赖注入
        doAutowired();
        //5、初始化HandlerMapping
        initHandlerMapping();

        System.out.println("PD Spring framework is init.");
    }
    
    private void initHandlerMapping() {
        if(ioc.isEmpty()){ return; }
        
        for (Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            //判断IOC中的类是否存在 @PDController注解，如果不存在，则略过本次循环
            if(!clazz.isAnnotationPresent(PDController.class)){ continue; }
            
            String url = "";
            //获取Controller的requestMapping的url配置
            if(clazz.isAnnotationPresent(PDRequestMapping.class)){
                PDRequestMapping requestMapping = clazz.getAnnotation(PDRequestMapping.class);
                url = requestMapping.value();
            }
            
            //获取该controller类中所有的方法
            Method [] methods = clazz.getMethods();
            for (Method method : methods) {
                
                //没有加RequestMapping注解的直接忽略
                if(!method.isAnnotationPresent(PDRequestMapping.class)){ continue; }
                
                //映射URL
                //获取Method的requestMapping的url配置
                PDRequestMapping requestMapping = method.getAnnotation(PDRequestMapping.class);
                String regex = ("/" + url + requestMapping.value()).replaceAll("/+", "/");
                //将url组装为一个正则
                Pattern pattern = Pattern.compile(regex);
                //加入到 handlerMapping的list中。
                handlerMapping.add(new Handler(pattern, entry.getValue(), method));
                System.out.println("mapping " + regex + "," + method);
            }
        }
    }

    private void doAutowired(){
        //如果ioc为空，则不进行DI操作
        if(ioc.isEmpty()){ return; }
        //循环ioc容器中的实例
        for (Entry<String, Object> entry : ioc.entrySet()) {
            //拿到实例对象中的所有属性
            Field [] fields = entry.getValue().getClass().getDeclaredFields();
            //循环该实例对象的所有属性
            for (Field field : fields) {
                //如果该属性不存在注解,则进行跳过
                if(!field.isAnnotationPresent(PDAutowired.class)){ continue; }
                
                PDAutowired autowired = field.getAnnotation(PDAutowired.class);
                //获取注解上填写的注入bean名称
                String beanName = autowired.value().trim();
                //如果名称为空,则通过属性的类型来当做bean名称
                if("".equals(beanName)){
                    beanName = field.getType().getName();
                }
                field.setAccessible(true); //设置私有属性的访问权限
                try {
                    //为属性赋值
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (Exception e) {
                    e.printStackTrace();
                    continue ;
                }
            }
        }
    }

    private void doInstance(){
        //判断存储类名称的List是否为空
        if(classNames.size() == 0){ return; }
        
        try{
            //循环类名称
            for (String className : classNames) {
                //获取类的class对象
                Class<?> clazz = Class.forName(className);
                //判断该类中是否存在PDController注解
                if(clazz.isAnnotationPresent(PDController.class)){

                    //取类的名称为bean名称  默认将首字母小写作为beanName
                    String beanName = lowerFirst(clazz.getSimpleName());
                    //实例化并放入至ioc容器中
                    ioc.put(beanName, clazz.newInstance());

                    //判断该类中是否存在PDService注解
                }else if(clazz.isAnnotationPresent(PDService.class)){

                    PDService service = clazz.getAnnotation(PDService.class);
                    //获取在PDService注解上填写的bean名称
                    String beanName = service.value();
                    //如果设置了名称，就用注解上的
                    if(!"".equals(beanName.trim())){
                        ioc.put(beanName, clazz.newInstance());
                        continue;
                    }

                    //如果没设，就按接口类型创建一个实例
                    Class<?>[] interfaces = clazz.getInterfaces();
                    for (Class<?> i : interfaces) {
                        ioc.put(i.getName(), clazz.newInstance());
                    }
                }else{
                    continue;
                }
            }
        }catch(Exception e){
            System.out.println("初始化类时,出现error,异常信息:" + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doScanner(String scanPackage) {
        //scanPackage = com.peng.demo
        //路径传过来，该路径下面的所有的类全部扫描进来的
        URL url = this.getClass().getClassLoader()
                .getResource("/" + scanPackage.replaceAll("\\.","/"));
        //一个包对应在系统层面即为一个文件夹,将路径转为File文件夹来表示和操作。
        File classPath = new File(url.getFile());

        //遍历该文件夹下的所有文件夹以及文件
        for (File file : classPath.listFiles()) {
            //如果该file对象是文件夹，则递归继续扫描
            if(file.isDirectory()){
                doScanner(scanPackage + "." + file.getName());
            }else {
                //判断该file文件对象是否为class文件，如果不为class文件则不进行扫猫。
                if(!file.getName().endsWith(".class")){ continue; }
                //如果为class文件，将class文件及其路径地址加入到List中
                //比如: com.peng.demo.mvc.controller.DemoController
                String className = (scanPackage + "." + file.getName()).replace(".class","");
                classNames.add(className);
            }
        }
    }

    private void doLoadConfig(String contextConfigLocation) {
        InputStream fis = null;
        try {
            //从resource中获取 application.perperties的输入流
            fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
            //利用Properties对象来读取配置文件
            contextConfig.load(fis);
        }catch(Exception e){
            System.out.println("加载配置文件时,出现error,异常信息:" + Arrays.toString(e.getStackTrace()));
        }finally{
            try {
                if(null != fis){
                    fis.close();
                }
            } catch (IOException e) {
                System.out.println("加载配置文件时,出现error,异常信息:" + Arrays.toString(e.getStackTrace()));
            }
        }
    }

    //将get请求的处理逻辑交给post一致来处理
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        this.doPost(req, resp);
    }

    
    /**
     * 执行业务处理
     */
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try{
            doDispatch(req,resp); //开始始匹配到对应的方方法
        }catch(Exception e){
            //如果匹配过程出现异常，将异常信息打印出去
            resp.getWriter().write("500 Exception,Details:\r\n" + Arrays.toString(e.getStackTrace()).replaceAll("\\[|\\]", "").replaceAll(",\\s", "\r\n"));
        }
    }

    /**
     * 匹配URL
     * @param req
     * @param resp
     * @return
     * @throws Exception
     */
    private void doDispatch(HttpServletRequest req,HttpServletResponse resp) throws Exception{
            //根据request获取相对应的handler
            Handler handler = getHandler(req);
            
            //如果没有匹配上，返回404错误
            if(handler == null){
                resp.getWriter().write("404 Not Found");
                return;
            }
            
            //获取方法的参数列表
            Class<?> [] paramTypes = handler.method.getParameterTypes();
            
            //保存所有需要自动赋值的参数值,最终将该数组传入至 method执行的反射的方法中.
            Object [] paramValues = new Object[paramTypes.length];
            
            //获取request中的参数key以及value
            Map<String,String[]> params = req.getParameterMap();
            
            //循环request中获取的参数信息
            for (Entry<String, String[]> param : params.entrySet()) {
                
                //将数组信息变为字符串信息  如  ["参数值1","参数值2"] - >   "参数值1,参数值2"
                String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");
                
                //判断在初始化handler时, 里面的paramIndexMapping是否有该参数的名称  如果找到匹配的对象，则开始填充参数值
                if(!handler.paramIndexMapping.containsKey(param.getKey())){continue;}
                //获取到该request的参数值在 method方法中所在的索引位置
                int index = handler.paramIndexMapping.get(param.getKey());
                //将参数的value值,转换为相关的类型加入到paramValues中
                paramValues[index] = convert(paramTypes[index],value);
            }
            
            
            //设置方法中的request和response对象
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;

            //执行method.invoke方法来完成方法的调用
            handler.method.invoke(handler.controller, paramValues);
    }
    
    private Handler getHandler(HttpServletRequest req) throws Exception{
        if(handlerMapping.isEmpty()){ return null; }
        //从request中获取url
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        //组装为相对路径路径
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        //循环list中的每一项handler值
        for (Handler handler : handlerMapping) {
            //如果handler中能匹配到相对应的url,则返回该handler，如果匹配不到则继续匹配
            Matcher matcher = handler.pattern.matcher(url);
            //如果没有匹配上继续下一个匹配
            if(!matcher.matches()){ continue; }
            return handler;
        }
        return null;
    }

    //url传过来的参数都是String类型的，HTTP是基于字符串协议
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        return value;
    }

    /**
     * Handler记录Controller中的RequestMapping和Method的对应关系
     * 内部类
     */
    private class Handler{

        protected Object controller;    //保存方法对应的bean实例
        protected Method method;        //保存映射的方法
        protected Pattern pattern;      //正则，保存对应的请求的URL地址
        protected Map<String,Integer> paramIndexMapping;    //存储参数顺序，key为参数名称，value为该参数在该方法中所存在的位置。

        /**
         * 构造一个Handler基本的参数
         * @param controller
         * @param method
         */
        protected Handler(Pattern pattern,Object controller,Method method){
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;

            paramIndexMapping = new HashMap<String,Integer>();
            //往paramIndexMapping中赋值
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method){
            //提取方法中加了@PDRequestParam注解的参数
            //为二维数组，原因在于 一个方法中有多个参数,每个参数上又可以有多个注解来修饰。
            Annotation [] [] pa = method.getParameterAnnotations();

            //循环第一层,其实循环的为参数级别的注解
            for (int i = 0; i < pa.length ; i ++) {
                //循环第二层,循环的为单个参数上的所有参数注解
                for(Annotation a : pa[i]){
                    //如果注解类型为PDRequestParam 
                    if(a instanceof PDRequestParam){
                        //获取该注解上的value值
                        String paramName = ((PDRequestParam) a).value();
                        //如果value值不为空,则进行放入paramIndexMapping中
                        //这要求在配置@PDRequestParam注解时，需要在其注解上指明该参数的名称
                        if(!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            //提取方法中的request和response参数
            Class<?> [] paramsTypes = method.getParameterTypes();
            //判断是否在方法的参数类型中存在request和response参数
            for (int i = 0; i < paramsTypes.length ; i ++) {
                Class<?> type = paramsTypes[i];
                //如果存在也需要把其也加入其中。
                if(type == HttpServletRequest.class ||
                   type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(), i);
                }
            }
        }
    }
    
    /**
     * 首字母小母
     * @param str
     * @return
     */
    private String lowerFirst(String str){
        char [] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }
}
