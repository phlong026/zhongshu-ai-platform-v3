package cn.iocoder.yudao.module.platform.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional real native Redis/MinIO fixtures. Explicit executable properties opt in; Docker remains the default. */
public final class TestNativeService {
    private TestNativeService(){ }
    public static GenericContainer<?> container(String image){return container(DockerImageName.parse(image));}
    public static GenericContainer<?> container(DockerImageName image){
        String kind=image.getRepository().contains("minio")?"minio":image.getRepository().equals("redis")?"redis":null;
        String executable=kind==null?null:System.getProperty("platform.test."+kind+".executable");
        return executable==null?new GenericContainer<>(image):new Native(image,kind,executable);
    }
    private static final class Native extends GenericContainer<Native> {
        private final String kind,executable;private final Map<Integer,Integer> ports=new HashMap<>();private Process process;private Path directory;
        private Native(DockerImageName image,String kind,String executable){super(image);this.kind=kind;this.executable=executable;}
        private int port(int original){return ports.computeIfAbsent(original,key->{try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}});}
        @Override public synchronized void start(){
            if(isRunning())return;
            try{
                directory=Files.createTempDirectory("codex-design-"+kind+"-");var command=new ArrayList<String>();command.add(executable);
                var args=new ArrayList<>(Arrays.asList(getCommandParts()));
                if(!args.isEmpty()&&(args.get(0).equals("redis-server")||args.get(0).equals("minio")))args.remove(0);
                if(kind.equals("redis")){command.addAll(args);command.addAll(List.of("--bind","127.0.0.1","--port",String.valueOf(port(6379)),"--save","","--appendonly","no"));}
                else {
                    args.replaceAll(value->value.equals("/data")?directory.resolve("data").toString():value);command.addAll(args);
                    command.addAll(List.of("--address","127.0.0.1:"+port(9000),"--console-address","127.0.0.1:"+port(9001)));
                }
                var builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("service.log").toFile());builder.environment().putAll(getEnvMap());process=builder.start();
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop,"native-test-"+kind));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);int main=port(kind.equals("redis")?6379:9000);
                while(System.nanoTime()<deadline){if(!process.isAlive())throw new IllegalStateException("Native "+kind+" exited: "+getLogs());try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",main),100);return;}catch(java.io.IOException pending){Thread.sleep(50);}}
                throw new IllegalStateException("Native "+kind+" startup timed out: "+getLogs());
            }catch(Exception e){stop();throw new IllegalStateException("Native service fixture could not start",e);}
        }
        @Override public synchronized void stop(){if(process!=null&&process.isAlive()){process.destroy();try{if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}}}
        @Override public String getHost(){return "127.0.0.1";}
        @Override public Integer getMappedPort(int original){return port(original);}
        @Override public boolean isRunning(){return process!=null&&process.isAlive();}
        @Override public String getLogs(){try{return directory==null?"":Files.readString(directory.resolve("service.log"));}catch(java.io.IOException e){return "Log unavailable";}}
        @Override public void close(){stop();}
    }
}
