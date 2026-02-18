use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::runtime::Builder;
use tokio_tungstenite::connect_async;
use futures_util::StreamExt;

static STOP: AtomicBool = AtomicBool::new(false);

// === ULTRA-MINIMAL BUFFER POOL ===
const BUFFER_SIZE: usize = 128; // max message size
const POOL_SIZE: usize = 1;    // only 1 buffer

#[no_mangle]
pub extern "system" fn Java_com_example_wslistener_WsService_startWs(
    env: JNIEnv,
    _class: JClass,
    url: JString,
) {
    let url: String = env.get_string(url).unwrap().into();

    // Single-threaded runtime for minimal memory
    let rt = Builder::new_current_thread().build().unwrap();

    rt.block_on(async move {
        let mut buffer = [0u8; BUFFER_SIZE]; // reuse for all messages

        while !STOP.load(Ordering::Relaxed) {
            if let Ok((mut ws_stream, _)) = connect_async(&url).await {
                while let Some(msg) = ws_stream.next().await {
                    if STOP.load(Ordering::Relaxed) { break; }

                    if let Ok(m) = msg {
                        let service_class = env.find_class("com/example/wslistener/WsService").unwrap();
                        let method_id = env.get_method_id(service_class, "notifyMessage", "(Ljava/lang/String;)V").unwrap();

                        let text = if m.is_text() {
                            m.to_text().unwrap()
                        } else if m.is_binary() {
                            let data = m.into_data();
                            let len = data.len().min(BUFFER_SIZE);
                            buffer[..len].copy_from_slice(&data[..len]);
                            std::str::from_utf8(&buffer[..len]).unwrap_or("")
                        } else { continue; };

                        // Truncate to 64 bytes
                        let truncated = &text[..text.len().min(BUFFER_SIZE)];
                        let jmsg = env.new_string(truncated).unwrap();

                        env.call_method_unchecked(
                            env.auto_local(jni::objects::JObject::null()),
                            method_id,
                            jni::signature::ReturnType::Void,
                            &[jni::objects::JValue::from(jmsg)],
                        ).unwrap();
                    }
                }
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_wslistener_WsService_stopWs(
    _env: JNIEnv,
    _class: JClass,
) {
    STOP.store(true, Ordering::Relaxed);
}
