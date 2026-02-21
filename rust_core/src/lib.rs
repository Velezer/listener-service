use jni::objects::{JClass, JObject, JString, JValue};
use jni::{JavaVM, JNIEnv};
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::runtime::Builder;
use tokio_tungstenite::connect_async;
use futures_util::StreamExt;

static STOP: AtomicBool = AtomicBool::new(false);

const BUFFER_SIZE: usize = 128;

/// Send a message string back to Kotlin via `WsService.onWsMessage(String)`.
/// Obtains a fresh JNIEnv from the cached JavaVM so this is safe to call from
/// any thread without holding a JNIEnv across await points.
fn notify_kotlin(vm: &JavaVM, msg: &str) {
    let mut env = match vm.attach_current_thread() {
        Ok(env) => env,
        Err(_) => return,
    };

    let jmsg = match env.new_string(msg) {
        Ok(s) => s,
        Err(_) => return,
    };

    let class = match env.find_class("com/listener/WsService") {
        Ok(c) => c,
        Err(_) => return,
    };

    let _ = env.call_static_method(
        class,
        "onWsMessage",
        "(Ljava/lang/String;)V",
        &[JValue::Object(&JObject::from(jmsg))],
    );
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_startWs(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) {
    let url: String = env.get_string(&url).unwrap().into();
    let vm = env.get_java_vm().unwrap();

    // Reset stop flag so the service can be restarted after a previous stop.
    STOP.store(false, Ordering::Relaxed);

    let rt = Builder::new_current_thread().enable_all().build().unwrap();

    rt.block_on(async move {
        let mut buffer = [0u8; BUFFER_SIZE];

        while !STOP.load(Ordering::Relaxed) {
            match connect_async(&url).await {
                Ok((mut ws_stream, _)) => {
                    while let Some(msg) = ws_stream.next().await {
                        if STOP.load(Ordering::Relaxed) {
                            break;
                        }

                        if let Ok(m) = msg {
                            let text = if m.is_text() {
                                m.to_text().unwrap_or("").to_string()
                            } else if m.is_binary() {
                                let data = m.into_data();
                                let len = data.len().min(BUFFER_SIZE);
                                buffer[..len].copy_from_slice(&data[..len]);
                                std::str::from_utf8(&buffer[..len])
                                    .unwrap_or("")
                                    .to_string()
                            } else {
                                continue;
                            };

                            let truncated = &text[..text.len().min(BUFFER_SIZE)];
                            notify_kotlin(&vm, truncated);
                        }
                    }
                }
                Err(_) => {
                    // Brief backoff before reconnecting
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                }
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_stopWs(
    _env: JNIEnv,
    _class: JClass,
) {
    STOP.store(true, Ordering::Relaxed);
}
