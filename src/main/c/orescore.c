#define _POSIX_C_SOURCE 200809L
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

typedef struct {
    pthread_mutex_t lock;
    jobject *items;
    size_t size;
    size_t capacity;
} ores_list;

typedef struct {
    pthread_mutex_t lock;
    char **keys;
    jobject *values;
    size_t size;
    size_t capacity;
} ores_map;

typedef struct {
    pthread_mutex_t lock;
    int fd;
} ores_file;

static void throw_class(JNIEnv *env, const char *name, const char *message) {
    jclass cls = (*env)->FindClass(env, name);
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void throw_illegal(JNIEnv *env, const char *message) {
    throw_class(env, "java/lang/IllegalArgumentException", message);
}

static void throw_state(JNIEnv *env, const char *message) {
    throw_class(env, "java/lang/IllegalStateException", message);
}

static void throw_io(JNIEnv *env, const char *operation) {
    char message[512];
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(errno));
    throw_class(env, "java/io/IOException", message);
}

static int ensure_list_capacity(JNIEnv *env, ores_list *list, size_t needed) {
    if (needed <= list->capacity) return 1;
    size_t next = list->capacity == 0 ? 8 : list->capacity;
    while (next < needed) {
        if (next > SIZE_MAX / 2) { throw_state(env, "native List capacity overflow"); return 0; }
        next *= 2;
    }
    jobject *items = (jobject *)realloc(list->items, next * sizeof(jobject));
    if (items == NULL) { throw_state(env, "native List allocation failed"); return 0; }
    memset(items + list->capacity, 0, (next - list->capacity) * sizeof(jobject));
    list->items = items;
    list->capacity = next;
    return 1;
}

static ores_list *as_list(JNIEnv *env, jlong handle) {
    ores_list *list = (ores_list *)(intptr_t)handle;
    if (list == NULL) throw_state(env, "native List handle is closed");
    return list;
}

JNIEXPORT jlong JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listCreate
  (JNIEnv *env, jclass cls) {
    (void)cls;
    ores_list *list = (ores_list *)calloc(1, sizeof(*list));
    if (list == NULL) { throw_state(env, "native List allocation failed"); return 0; }
    pthread_mutex_init(&list->lock, NULL);
    return (jlong)(intptr_t)list;
}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listFree
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    ores_list *list = (ores_list *)(intptr_t)handle;
    if (list == NULL) return;
    pthread_mutex_lock(&list->lock);
    for (size_t i = 0; i < list->size; i++) {
        if (list->items[i] != NULL) (*env)->DeleteGlobalRef(env, list->items[i]);
    }
    free(list->items);
    list->items = NULL;
    list->size = list->capacity = 0;
    pthread_mutex_unlock(&list->lock);
    pthread_mutex_destroy(&list->lock);
    free(list);
}

JNIEXPORT jint JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listSize
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return 0;
    pthread_mutex_lock(&list->lock); size_t size = list->size; pthread_mutex_unlock(&list->lock);
    if (size > INT32_MAX) { throw_state(env, "native List exceeds JVM bridge index range"); return 0; }
    return (jint)size;
}

JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listGet
  (JNIEnv *env, jclass cls, jlong handle, jint index) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return NULL;
    pthread_mutex_lock(&list->lock);
    if (index < 0 || (size_t)index >= list->size) {
        pthread_mutex_unlock(&list->lock); throw_illegal(env, "native List index out of range"); return NULL;
    }
    jobject result = (*env)->NewLocalRef(env, list->items[index]);
    pthread_mutex_unlock(&list->lock);
    return result;
}

JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listSet
  (JNIEnv *env, jclass cls, jlong handle, jint index, jobject value) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return NULL;
    pthread_mutex_lock(&list->lock);
    if (index < 0 || (size_t)index >= list->size) {
        pthread_mutex_unlock(&list->lock); throw_illegal(env, "native List index out of range"); return NULL;
    }
    jobject next = value == NULL ? NULL : (*env)->NewGlobalRef(env, value);
    if (value != NULL && next == NULL) { pthread_mutex_unlock(&list->lock); return NULL; }
    jobject old = list->items[index];
    jobject result = old == NULL ? NULL : (*env)->NewLocalRef(env, old);
    list->items[index] = next;
    if (old != NULL) (*env)->DeleteGlobalRef(env, old);
    pthread_mutex_unlock(&list->lock);
    return result;
}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listAdd
  (JNIEnv *env, jclass cls, jlong handle, jobject value) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return;
    pthread_mutex_lock(&list->lock);
    if (!ensure_list_capacity(env, list, list->size + 1)) { pthread_mutex_unlock(&list->lock); return; }
    jobject ref = value == NULL ? NULL : (*env)->NewGlobalRef(env, value);
    if (value != NULL && ref == NULL) { pthread_mutex_unlock(&list->lock); return; }
    list->items[list->size++] = ref;
    pthread_mutex_unlock(&list->lock);
}

JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listRemove
  (JNIEnv *env, jclass cls, jlong handle, jint index) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return NULL;
    pthread_mutex_lock(&list->lock);
    if (index < 0 || (size_t)index >= list->size) {
        pthread_mutex_unlock(&list->lock); throw_illegal(env, "native List index out of range"); return NULL;
    }
    jobject old = list->items[index];
    jobject result = old == NULL ? NULL : (*env)->NewLocalRef(env, old);
    if (old != NULL) (*env)->DeleteGlobalRef(env, old);
    memmove(&list->items[index], &list->items[index + 1],
            (list->size - (size_t)index - 1) * sizeof(jobject));
    list->size--;
    list->items[list->size] = NULL;
    pthread_mutex_unlock(&list->lock);
    return result;
}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listClear
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return;
    pthread_mutex_lock(&list->lock);
    for (size_t i = 0; i < list->size; i++) if (list->items[i] != NULL) (*env)->DeleteGlobalRef(env, list->items[i]);
    memset(list->items, 0, list->capacity * sizeof(jobject)); list->size = 0;
    pthread_mutex_unlock(&list->lock);
}

JNIEXPORT jobjectArray JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_listSnapshot
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    ores_list *list = as_list(env, handle); if (list == NULL) return NULL;
    jclass objectClass = (*env)->FindClass(env, "java/lang/Object"); if (objectClass == NULL) return NULL;
    pthread_mutex_lock(&list->lock);
    if (list->size > INT32_MAX) { pthread_mutex_unlock(&list->lock); throw_state(env, "native List too large"); return NULL; }
    jobjectArray out = (*env)->NewObjectArray(env, (jsize)list->size, objectClass, NULL);
    if (out != NULL) for (size_t i = 0; i < list->size; i++) (*env)->SetObjectArrayElement(env, out, (jsize)i, list->items[i]);
    pthread_mutex_unlock(&list->lock);
    return out;
}

static char *copy_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *raw = (*env)->GetStringUTFChars(env, value, NULL); if (raw == NULL) return NULL;
    size_t len = strlen(raw); char *copy = (char *)malloc(len + 1);
    if (copy != NULL) memcpy(copy, raw, len + 1);
    (*env)->ReleaseStringUTFChars(env, value, raw);
    if (copy == NULL) throw_state(env, "native Map key allocation failed");
    return copy;
}

static ores_map *as_map(JNIEnv *env, jlong handle) {
    ores_map *map = (ores_map *)(intptr_t)handle;
    if (map == NULL) throw_state(env, "native Map/struct handle is closed");
    return map;
}

static ssize_t map_find(ores_map *map, const char *key) {
    for (size_t i = 0; i < map->size; i++) if (strcmp(map->keys[i], key) == 0) return (ssize_t)i;
    return -1;
}

static int ensure_map_capacity(JNIEnv *env, ores_map *map, size_t needed) {
    if (needed <= map->capacity) return 1;
    size_t next = map->capacity == 0 ? 8 : map->capacity;
    while (next < needed) {
        if (next > SIZE_MAX / 2) { throw_state(env, "native Map capacity overflow"); return 0; }
        next *= 2;
    }
    char **keys = (char **)realloc(map->keys, next * sizeof(char *));
    if (keys == NULL) { throw_state(env, "native Map key table allocation failed"); return 0; }
    jobject *values = (jobject *)realloc(map->values, next * sizeof(jobject));
    if (values == NULL) { map->keys = keys; throw_state(env, "native Map value table allocation failed"); return 0; }
    memset(keys + map->capacity, 0, (next - map->capacity) * sizeof(char *));
    memset(values + map->capacity, 0, (next - map->capacity) * sizeof(jobject));
    map->keys = keys; map->values = values; map->capacity = next; return 1;
}

JNIEXPORT jlong JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapCreate
  (JNIEnv *env, jclass cls) {
    (void)cls; ores_map *map = (ores_map *)calloc(1, sizeof(*map));
    if (map == NULL) { throw_state(env, "native Map allocation failed"); return 0; }
    pthread_mutex_init(&map->lock, NULL); return (jlong)(intptr_t)map;
}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapFree
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; ores_map *map = (ores_map *)(intptr_t)handle; if (map == NULL) return;
    pthread_mutex_lock(&map->lock);
    for (size_t i = 0; i < map->size; i++) {
        free(map->keys[i]); if (map->values[i] != NULL) (*env)->DeleteGlobalRef(env, map->values[i]);
    }
    free(map->keys); free(map->values); pthread_mutex_unlock(&map->lock);
    pthread_mutex_destroy(&map->lock); free(map);
}

JNIEXPORT jint JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapSize
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; ores_map *map = as_map(env, handle); if (map == NULL) return 0;
    pthread_mutex_lock(&map->lock); size_t n = map->size; pthread_mutex_unlock(&map->lock);
    if (n > INT32_MAX) { throw_state(env, "native Map too large"); return 0; } return (jint)n;
}

static int with_map_key(JNIEnv *env, jlong handle, jstring key, ores_map **out, char **raw) {
    *out = as_map(env, handle); if (*out == NULL) return 0;
    *raw = copy_jstring(env, key); return *raw != NULL;
}

JNIEXPORT jboolean JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapContains
  (JNIEnv *env, jclass cls, jlong handle, jstring key) {
    (void)cls; ores_map *map; char *raw; if (!with_map_key(env, handle, key, &map, &raw)) return JNI_FALSE;
    pthread_mutex_lock(&map->lock); int yes = map_find(map, raw) >= 0; pthread_mutex_unlock(&map->lock); free(raw);
    return yes ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapGet
  (JNIEnv *env, jclass cls, jlong handle, jstring key) {
    (void)cls; ores_map *map; char *raw; if (!with_map_key(env, handle, key, &map, &raw)) return NULL;
    pthread_mutex_lock(&map->lock); ssize_t at = map_find(map, raw);
    jobject result = at < 0 || map->values[at] == NULL ? NULL : (*env)->NewLocalRef(env, map->values[at]);
    pthread_mutex_unlock(&map->lock); free(raw); return result;
}

JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapPut
  (JNIEnv *env, jclass cls, jlong handle, jstring key, jobject value) {
    (void)cls; ores_map *map; char *raw; if (!with_map_key(env, handle, key, &map, &raw)) return NULL;
    pthread_mutex_lock(&map->lock); ssize_t at = map_find(map, raw);
    jobject next = value == NULL ? NULL : (*env)->NewGlobalRef(env, value);
    if (value != NULL && next == NULL) { pthread_mutex_unlock(&map->lock); free(raw); return NULL; }
    jobject previous = NULL;
    if (at >= 0) {
        jobject old = map->values[at]; previous = old == NULL ? NULL : (*env)->NewLocalRef(env, old);
        map->values[at] = next; if (old != NULL) (*env)->DeleteGlobalRef(env, old); free(raw);
    } else {
        if (!ensure_map_capacity(env, map, map->size + 1)) {
            if (next != NULL) (*env)->DeleteGlobalRef(env, next);
            pthread_mutex_unlock(&map->lock); free(raw); return NULL;
        }
        map->keys[map->size] = raw; map->values[map->size] = next; map->size++;
    }
    pthread_mutex_unlock(&map->lock); return previous;
}

JNIEXPORT jobject JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapRemove
  (JNIEnv *env, jclass cls, jlong handle, jstring key) {
    (void)cls; ores_map *map; char *raw; if (!with_map_key(env, handle, key, &map, &raw)) return NULL;
    pthread_mutex_lock(&map->lock); ssize_t at = map_find(map, raw); free(raw);
    if (at < 0) { pthread_mutex_unlock(&map->lock); return NULL; }
    jobject old = map->values[at]; jobject result = old == NULL ? NULL : (*env)->NewLocalRef(env, old);
    free(map->keys[at]); if (old != NULL) (*env)->DeleteGlobalRef(env, old);
    memmove(&map->keys[at], &map->keys[at+1], (map->size-(size_t)at-1)*sizeof(char*));
    memmove(&map->values[at], &map->values[at+1], (map->size-(size_t)at-1)*sizeof(jobject));
    map->size--; map->keys[map->size]=NULL; map->values[map->size]=NULL;
    pthread_mutex_unlock(&map->lock); return result;
}

JNIEXPORT jobjectArray JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapKeys
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; ores_map *map=as_map(env,handle); if(map==NULL)return NULL;
    jclass stringClass=(*env)->FindClass(env,"java/lang/String"); if(stringClass==NULL)return NULL;
    pthread_mutex_lock(&map->lock); jobjectArray out=(*env)->NewObjectArray(env,(jsize)map->size,stringClass,NULL);
    if(out!=NULL) for(size_t i=0;i<map->size;i++){jstring s=(*env)->NewStringUTF(env,map->keys[i]);(*env)->SetObjectArrayElement(env,out,(jsize)i,s);(*env)->DeleteLocalRef(env,s);}
    pthread_mutex_unlock(&map->lock); return out;
}

JNIEXPORT jobjectArray JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapValues
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; ores_map *map=as_map(env,handle); if(map==NULL)return NULL;
    jclass objectClass=(*env)->FindClass(env,"java/lang/Object"); if(objectClass==NULL)return NULL;
    pthread_mutex_lock(&map->lock); jobjectArray out=(*env)->NewObjectArray(env,(jsize)map->size,objectClass,NULL);
    if(out!=NULL) for(size_t i=0;i<map->size;i++)(*env)->SetObjectArrayElement(env,out,(jsize)i,map->values[i]);
    pthread_mutex_unlock(&map->lock); return out;
}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_mapClear
  (JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; ores_map *map=as_map(env,handle); if(map==NULL)return;
    pthread_mutex_lock(&map->lock); for(size_t i=0;i<map->size;i++){free(map->keys[i]);if(map->values[i]!=NULL)(*env)->DeleteGlobalRef(env,map->values[i]);map->keys[i]=NULL;map->values[i]=NULL;}map->size=0;pthread_mutex_unlock(&map->lock);
}

static ores_file *as_file(JNIEnv *env, jlong handle) {
    ores_file *file=(ores_file *)(intptr_t)handle; if(file==NULL||file->fd<0)throw_state(env,"File is closed"); return file;
}

JNIEXPORT jlong JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileOpen
  (JNIEnv *env,jclass cls,jstring path,jstring mode){
    (void)cls; char *p=copy_jstring(env,path),*m=copy_jstring(env,mode); if(p==NULL||m==NULL){free(p);free(m);return 0;}
    int flags=0; mode_t perms=0666;
    if(strcmp(m,"r")==0)flags=O_RDONLY;
    else if(strcmp(m,"r+")==0)flags=O_RDWR;
    else if(strcmp(m,"w")==0)flags=O_WRONLY|O_CREAT|O_TRUNC;
    else if(strcmp(m,"w+")==0)flags=O_RDWR|O_CREAT|O_TRUNC;
    else if(strcmp(m,"a")==0)flags=O_WRONLY|O_CREAT|O_APPEND;
    else if(strcmp(m,"a+")==0)flags=O_RDWR|O_CREAT|O_APPEND;
    else {free(p);free(m);throw_illegal(env,"File mode must be r, r+, w, w+, a, or a+");return 0;}
    int fd; do{fd=open(p,flags,perms);}while(fd<0&&errno==EINTR); free(p);free(m);
    if(fd<0){throw_io(env,"open");return 0;}
    ores_file *file=(ores_file*)calloc(1,sizeof(*file)); if(file==NULL){close(fd);throw_state(env,"File handle allocation failed");return 0;}
    pthread_mutex_init(&file->lock,NULL);file->fd=fd;return(jlong)(intptr_t)file;
}

JNIEXPORT jbyteArray JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileRead
  (JNIEnv *env,jclass cls,jlong handle,jint max_bytes){
    (void)cls;if(max_bytes<0){throw_illegal(env,"File.read maxBytes must be nonnegative");return NULL;}
    ores_file *file=as_file(env,handle);if(file==NULL)return NULL;pthread_mutex_lock(&file->lock);
    size_t cap=max_bytes==INT32_MAX?8192:(size_t)max_bytes; if(cap>8192)cap=8192; if(cap==0)cap=1;
    unsigned char *buf=(unsigned char*)malloc(cap);size_t size=0;
    if(buf==NULL){pthread_mutex_unlock(&file->lock);throw_state(env,"File.read allocation failed");return NULL;}
    while(size<(size_t)max_bytes){
      if(size==cap){size_t next=cap*2;if(next>(size_t)max_bytes)next=(size_t)max_bytes;unsigned char*n=(unsigned char*)realloc(buf,next);if(n==NULL){free(buf);pthread_mutex_unlock(&file->lock);throw_state(env,"File.read allocation failed");return NULL;}buf=n;cap=next;}
      size_t room=cap-size;ssize_t n;do{n=read(file->fd,buf+size,room);}while(n<0&&errno==EINTR);
      if(n<0){free(buf);pthread_mutex_unlock(&file->lock);throw_io(env,"read");return NULL;}if(n==0)break;size+=(size_t)n;
    }
    pthread_mutex_unlock(&file->lock);jbyteArray out=(*env)->NewByteArray(env,(jsize)size);if(out!=NULL&&size>0)(*env)->SetByteArrayRegion(env,out,0,(jsize)size,(jbyte*)buf);free(buf);return out;
}

JNIEXPORT jint JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileWrite
  (JNIEnv *env,jclass cls,jlong handle,jbyteArray bytes,jint offset,jint length){
    (void)cls;if(bytes==NULL||offset<0||length<0||offset+length>(*env)->GetArrayLength(env,bytes)){throw_illegal(env,"File.write byte range invalid");return -1;}
    ores_file*file=as_file(env,handle);if(file==NULL)return-1;jbyte*raw=(*env)->GetByteArrayElements(env,bytes,NULL);if(raw==NULL)return-1;
    pthread_mutex_lock(&file->lock);size_t written=0;while(written<(size_t)length){ssize_t n;do{n=write(file->fd,raw+offset+written,(size_t)length-written);}while(n<0&&errno==EINTR);if(n<0){pthread_mutex_unlock(&file->lock);(*env)->ReleaseByteArrayElements(env,bytes,raw,JNI_ABORT);throw_io(env,"write");return-1;}written+=(size_t)n;}pthread_mutex_unlock(&file->lock);(*env)->ReleaseByteArrayElements(env,bytes,raw,JNI_ABORT);return(jint)written;
}

JNIEXPORT jlong JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileSeek
  (JNIEnv*env,jclass cls,jlong handle,jlong offset,jint whence){(void)cls;int native_whence=whence==0?SEEK_SET:whence==1?SEEK_CUR:whence==2?SEEK_END:-1;if(native_whence<0){throw_illegal(env,"File.seek whence must be 0, 1, or 2");return-1;}ores_file*f=as_file(env,handle);if(f==NULL)return-1;pthread_mutex_lock(&f->lock);off_t at=lseek(f->fd,(off_t)offset,native_whence);pthread_mutex_unlock(&f->lock);if(at==(off_t)-1){throw_io(env,"lseek");return-1;}return(jlong)at;}

JNIEXPORT jlong JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileSize
  (JNIEnv*env,jclass cls,jlong handle){(void)cls;ores_file*f=as_file(env,handle);if(f==NULL)return-1;struct stat st;pthread_mutex_lock(&f->lock);int rc=fstat(f->fd,&st);pthread_mutex_unlock(&f->lock);if(rc<0){throw_io(env,"fstat");return-1;}return(jlong)st.st_size;}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileFlush
  (JNIEnv*env,jclass cls,jlong handle){(void)cls;ores_file*f=as_file(env,handle);if(f==NULL)return;pthread_mutex_lock(&f->lock);int rc;do{rc=fsync(f->fd);}while(rc<0&&errno==EINTR);pthread_mutex_unlock(&f->lock);if(rc<0)throw_io(env,"fsync");}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_fileClose
  (JNIEnv*env,jclass cls,jlong handle){(void)cls;ores_file*f=(ores_file*)(intptr_t)handle;if(f==NULL)return;pthread_mutex_lock(&f->lock);int fd=f->fd;f->fd=-1;pthread_mutex_unlock(&f->lock);if(fd>=0){int rc;do{rc=close(fd);}while(rc<0&&errno==EINTR);if(rc<0)throw_io(env,"close");}pthread_mutex_destroy(&f->lock);free(f);}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_threadSleepMillis
  (JNIEnv*env,jclass cls,jlong millis){(void)cls;if(millis<0){throw_illegal(env,"thread.sleep millis must be nonnegative");return;}struct timespec req={(time_t)(millis/1000),(long)((millis%1000)*1000000L)},rem;while(nanosleep(&req,&rem)<0){if(errno!=EINTR){throw_state(env,"native nanosleep failed");return;}req=rem;}}

JNIEXPORT void JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_threadYield
  (JNIEnv*env,jclass cls){(void)env;(void)cls;(void)sched_yield();}

JNIEXPORT jlong JNICALL Java_dev_oreslang_runtime_NativeCoreBridge_threadCurrentId
  (JNIEnv*env,jclass cls){(void)env;(void)cls;return(jlong)(uintptr_t)pthread_self();}
