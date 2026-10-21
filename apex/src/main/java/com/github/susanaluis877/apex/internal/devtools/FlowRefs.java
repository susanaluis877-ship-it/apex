package com.github.susanaluis877.apex.internal.devtools;

import com.github.susanaluis877.apex.flow.Flow;
import com.github.susanaluis877.apex.flow.FlowValidationException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/**
 * 按名字加载宿主代码里用 Java DSL 写的流程，让调试桥能直接运行、检查、导出它们，不用先转成 JSON。
 *
 * <p>引用格式：
 * <ul>
 *   <li>{@code com.foo.Flows#addAlarm}：静态、无参、返回 {@link Flow} 的方法。</li>
 *   <li>{@code com.foo.AddAlarmFlow}：类里有静态无参方法 {@code flow()}，或者类实现了 {@code Supplier<Flow>}（要有无参构造）。</li>
 * </ul>
 * 改了 DSL 要重新编译安装宿主才会生效。
 */
public final class FlowRefs {

    private FlowRefs() {
    }

    /**
     * @throws DevtoolsException 类或方法找不到、签名不对、方法里抛了其他异常
     * @throws FlowValidationException 流程构建时校验不通过（原样抛出，调用方会转换成带路径的问题列表）
     */
    public static Flow load(ClassLoader loader, String ref) throws DevtoolsException {
        int hash = ref.indexOf('#');
        String className = hash >= 0 ? ref.substring(0, hash) : ref;
        String methodName = hash >= 0 ? ref.substring(hash + 1) : null;
        Class<?> type;
        try {
            type = Class.forName(className, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new DevtoolsException(DevtoolsException.INVALID_FLOW, "class " + className + " not found in the "
                    + "host app; was it rebuilt and reinstalled after the flow was added? (" + e + ")");
        }
        try {
            if (methodName != null) {
                return invoke(type, methodName, ref);
            }
            for (Method m : type.getDeclaredMethods()) {
                if (m.getName().equals("flow") && m.getParameterTypes().length == 0) {
                    return invoke(type, "flow", ref);
                }
            }
            if (Supplier.class.isAssignableFrom(type)) {
                Object supplier = type.getDeclaredConstructor().newInstance();
                Object flow = ((Supplier<?>) supplier).get();
                if (flow instanceof Flow) {
                    return (Flow) flow;
                }
                throw bad(ref, "Supplier returned " + flow);
            }
            throw bad(ref, "write it as Class#method, add a static flow() method, or implement Supplier<Flow>");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FlowValidationException) {
                throw (FlowValidationException) cause;
            }
            throw new DevtoolsException(DevtoolsException.INVALID_FLOW, ref + " threw " + cause);
        } catch (ReflectiveOperationException e) {
            throw bad(ref, e.toString());
        }
    }

    private static Flow invoke(Class<?> type, String name, String ref) throws ReflectiveOperationException,
            DevtoolsException {
        Method method;
        try {
            method = type.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw bad(ref, "no method " + name + "() without parameters in " + type.getName());
        }
        if (!Modifier.isStatic(method.getModifiers()) || !Flow.class.isAssignableFrom(method.getReturnType())) {
            throw bad(ref, name + "() must be static and return Flow");
        }
        method.setAccessible(true);
        return (Flow) method.invoke(null);
    }

    private static DevtoolsException bad(String ref, String why) {
        return new DevtoolsException(DevtoolsException.INVALID_FLOW, "cannot load flow " + ref + ": " + why);
    }
}
