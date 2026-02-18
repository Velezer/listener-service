use jni::objects::{JClass, JString, JObject, JValue};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::runtime::Builder;
use tokio_tungstenite::connect_async;
use futures_util::StreamExt;

static STOP: AtomicBool = AtomicBool::new(false);

const BUFFER_SIZE: usize = 128;

#[no_mangle]
pub extern "system" fn Java_com_example_wslistener_WsService_startWs(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) {
    // FIX 1: borrow url
    let url: String = env.get_string(&url).unwrap().into();

    let rt = Builder::new_current_thread().enable_all().build().unwrap();

    rt.block_on(async move {
        let mut buffer = [0u8; BUFFER_SIZE];

        while !STOP.load(Ordering::Relaxed) {
            if let Ok((mut ws_stream, _)) = connect_async(&url).await {
                while let Some(msg) = ws_stream.next().await {
                    if STOP.load(Ordering::Relaxed) {
                        break;
                    }

                    if let Ok(m) = msg {
                        let text = if m.is_text() {
                            m.to_text().unwrap_or("")
                        } else if m.is_binary() {
                            let data = m.into_data();
                            let len = data.len().min(BUFFER_SIZE);
                            buffer[..len].copy_from_slice(&data[..len]);
                            std::str::from_utf8(&buffer[..len]).unwrap_or("")
                        } else {
                            continue;
                        };

                        let truncated = &text[..text.len().min(BUFFER_SIZE)];
                        let jmsg = env.new_string(truncated).unwrap();

                        // FIX 2: Call static method properly
                        let service_class = env
                            .find_class("com/listener/WsService")
                            .unwrap();

                        env.call_static_method(
                            service_class,
                            "notifyMessage",
                            "(Ljava/lang/String;)V",
                            &[JValue::Object(&JObject::from(jmsg))],
                        )
                        .unwrap();
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
