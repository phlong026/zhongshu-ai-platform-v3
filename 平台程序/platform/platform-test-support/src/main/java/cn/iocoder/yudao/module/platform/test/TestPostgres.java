package cn.iocoder.yudao.module.platform.test;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.sql.*;
import java.util.UUID;

/** Real PostgreSQL in Docker by default; an explicitly supplied disposable local cluster also works.
 * Every external instance gets its own database, so clean/migrate and parallel suites stay isolated. */
public final class TestPostgres {
    private TestPostgres() { }
    public static PostgreSQLContainer<?> database(String image){return database(DockerImageName.parse(image));}
    public static PostgreSQLContainer<?> database(DockerImageName image){
        return System.getProperty("platform.test.jdbc.url")==null?new PostgreSQLContainer<>(image):new External(image);
    }
    private static final class External extends PostgreSQLContainer<External> {
        private final String base=System.getProperty("platform.test.jdbc.url");
        private final String name="codex_test_"+UUID.randomUUID().toString().replace("-","");
        private boolean started;
        private External(DockerImageName image){super(image);}
        @Override public synchronized void start(){
            if(started)return;
            try(var connection=DriverManager.getConnection(base,getUsername(),getPassword());var statement=connection.createStatement()){
                statement.execute("CREATE DATABASE "+name);started=true;
            }catch(SQLException e){throw new IllegalStateException("Disposable PostgreSQL database could not be created",e);}
        }
        @Override public synchronized void stop(){
            if(!started)return;
            try(var connection=DriverManager.getConnection(base,getUsername(),getPassword());var statement=connection.createStatement()){
                statement.execute("DROP DATABASE "+name+" WITH (FORCE)");started=false;
            }catch(SQLException e){throw new IllegalStateException("Disposable PostgreSQL database could not be removed",e);}
        }
        @Override public String getJdbcUrl(){return base.substring(0,base.lastIndexOf('/')+1)+name+"?connectTimeout=5";}
        @Override public String getUsername(){return System.getProperty("platform.test.jdbc.username","postgres");}
        @Override public String getPassword(){return System.getProperty("platform.test.jdbc.password","");}
        @Override public String getDatabaseName(){return name;}
        @Override public String getHost(){return URI.create(base.substring(5)).getHost();}
        @Override public Integer getMappedPort(int port){int value=URI.create(base.substring(5)).getPort();return value<0?5432:value;}
        @Override public boolean isRunning(){return started;}
        @Override public void close(){stop();}
    }
}
