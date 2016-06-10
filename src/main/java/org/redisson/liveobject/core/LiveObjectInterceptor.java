package org.redisson.liveobject.core;

import java.lang.reflect.Method;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldProxy;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import org.redisson.RedissonClient;
import org.redisson.RedissonMap;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import org.redisson.core.RMap;
import org.redisson.liveobject.CodecProvider;
import org.redisson.liveobject.annotation.REntity;

/**
 *
 * @author Rui Gu (https://github.com/jackygurui)
 */
public class LiveObjectInterceptor {

    public interface Getter {

        Object getValue();
    }

    public interface Setter {

        void setValue(Object value);
    }

    private final RedissonClient redisson;
    private final CodecProvider codecProvider;
    private final Class originalClass;
    private final String idFieldName;
    private final REntity.NamingScheme namingScheme;
    private final Class<? extends Codec> codecClass;

    public LiveObjectInterceptor(RedissonClient redisson, CodecProvider codecProvider, Class entityClass, String idFieldName) throws Exception {
        this.redisson = redisson;
        this.codecProvider = codecProvider;
        this.originalClass = entityClass;
        this.idFieldName = idFieldName;
        REntity anno = ((REntity) entityClass.getAnnotation(REntity.class));
        this.namingScheme = anno.namingScheme().newInstance();
        this.codecClass = anno.codec();
    }

    @RuntimeType
    public Object intercept(
            @Origin Method method,
            @AllArguments Object[] args,
            @This Object me,
            @FieldValue("liveObjectId") Object id,
            @FieldProxy("liveObjectId") Setter idSetter,
            @FieldProxy("liveObjectId") Getter idGetter,
            @FieldValue("liveObjectLiveMap") RMap map,
            @FieldProxy("liveObjectLiveMap") Setter mapSetter,
            @FieldProxy("liveObjectLiveMap") Getter mapGetter
    ) throws Exception {
        if ("setLiveObjectId".equals(method.getName())) {
            //TODO: distributed locking maybe required.
            String idKey = getMapKey(args[0]);
            if (map != null) {
                try {
                    map.rename(getMapKey(args[0]));
                } catch (RedisException e) {
                    if (e.getMessage() == null || !e.getMessage().startsWith("ERR no such key")) {
                        throw e;
                    }
                    //key may already renamed by others.
                }
            }
            RMap<Object, Object> liveMap = redisson.getMap(idKey,
                    codecProvider.getCodec(codecClass, RedissonMap.class, idKey));
            mapSetter.setValue(liveMap);

            return null;
        }

        if ("getLiveObjectId".equals(method.getName())) {
            if (map == null) {
                return null;
            }
            return namingScheme.resolveId(map.getName());
        }

        if ("getLiveObjectLiveMap".equals(method.getName())) {
            return map;
        }

        throw new NoSuchMethodException();
    }

    private String getMapKey(Object id) {
        return namingScheme.getName(originalClass, idFieldName, id);
    }

}
