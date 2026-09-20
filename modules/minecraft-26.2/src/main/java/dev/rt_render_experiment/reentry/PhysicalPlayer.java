package dev.rt_render_experiment.reentry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;


public final class PhysicalPlayer {
    public interface State {
        java.util.UUID rt_render_experiment$entity();void rt_render_experiment$entity(java.util.UUID id);
        boolean rt_render_experiment$physical();void rt_render_experiment$physical(boolean physical);
    }
    public interface ModelSubmit { boolean rt_render_experiment$physicalModel(); }
    private static final ThreadLocal<Boolean> SUBMITTING=ThreadLocal.withInitial(()->false);
    private PhysicalPlayer() {}
    public static boolean submitting() { return SUBMITTING.get(); }
    public static SubmitNodeCollector modelsOnly(SubmitNodeCollector delegate) { return (SubmitNodeCollector)proxy(SubmitNodeCollector.class,delegate); }
    private static Object proxy(Class<?> type,OrderedSubmitNodeCollector delegate) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()) {
                case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->"RtRenderExperiment secondary player models";
            };
            if(method.getName().equals("order"))return proxy(OrderedSubmitNodeCollector.class,((SubmitNodeCollector)delegate).order((Integer)args[0]));
            if(!method.getName().startsWith("submitModel"))return null;
            boolean previous=SUBMITTING.get();SUBMITTING.set(true);
            try { return method.invoke(delegate,args); }
            catch(InvocationTargetException failure) { throw failure.getCause(); }
            finally { SUBMITTING.set(previous); }
        });
    }
}
