package com.mcsirius.cloud.redis.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class SpelUtil {
    /**
     * 用于SpEL表达式解析.
     */
    private static final SpelExpressionParser parser = new SpelExpressionParser();

    public static String generateKeyBySpEL(String spELString, ProceedingJoinPoint joinPoint) {
        // 通过joinPoint获取被注解方法
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        Parameter[] parameters = method.getParameters();
        // 使用spring的DefaultParameterNameDiscoverer获取方法形参名数组
        // 解析过后的Spring表达式对象
        Expression expression = parser.parseExpression(spELString);
        // spring的表达式上下文对象
        EvaluationContext context = new StandardEvaluationContext();
        // 通过joinPoint获取被注解方法的形参
        Object[] args = joinPoint.getArgs();
        // 给上下文赋值
        for(int i = 0 ; i < args.length ; i++) {
            context.setVariable(parameters[i].getName(), args[i]);
        }
        // 表达式从上下文中计算出实际参数值
        /*如:
        @annotation(key="#student.name")
        method(Student student)
        那么就可以解析出方法形参的某属性值，return “xiaoming”;
        */
        return expression.getValue(context).toString();
    }
}
