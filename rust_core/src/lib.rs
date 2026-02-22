use jni::objects::{JClass, JObject, JString, JValue};
use jni::{JavaVM, JNIEnv};
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::runtime::Builder;
use tokio_tungstenite::connect_async;
use futures_util::StreamExt;

static STOP: AtomicBool = AtomicBool::new(false);

const BUFFER_SIZE: usize = 128;

/// Call a static void method on WsService with a single String argument.
fn call_kotlin(vm: &JavaVM, method: &str, msg: &str) {
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
        method,
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

    STOP.store(false, Ordering::Relaxed);
    call_kotlin(&vm, "onWsStarted", "WebSocket listener started");

    let rt = Builder::new_current_thread().enable_all().build().unwrap();

    rt.block_on(async move {
        let mut buffer = [0u8; BUFFER_SIZE];
        let mut attempt: u32 = 0;

        while !STOP.load(Ordering::Relaxed) {
            attempt += 1;
            call_kotlin(&vm, "onWsConnecting", &format!("Connecting (attempt {})...", attempt));

            match connect_async(&url).await {
                Ok((mut ws_stream, _)) => {
                    attempt = 0;
                    call_kotlin(&vm, "onWsConnected", &url);

                    while let Some(msg) = ws_stream.next().await {
                        if STOP.load(Ordering::Relaxed) {
                            break;
                        }

                        match msg {
                            Ok(m) => {
                                let text = if m.is_text() {
                                    m.to_text().unwrap_or("").to_string()
                                } else if m.is_binary() {
                                    let data = m.into_data();
                                    let len = data.len().min(BUFFER_SIZE);
                                    buffer[..len].copy_from_slice(&data[..len]);
                                    std::str::from_utf8(&buffer[..len])
                                        .unwrap_or("")
                                        .to_string()
                                } else if m.is_close() {
                                    call_kotlin(&vm, "onWsDisconnected", "Server closed connection");
                                    break;
                                } else {
                                    continue;
                                };

                                let truncated = &text[..text.len().min(BUFFER_SIZE)];
                                call_kotlin(&vm, "onWsMessage", truncated);
                            }
                            Err(e) => {
                                call_kotlin(&vm, "onWsError", &format!("Read error: {}", e));
                                break;
                            }
                        }
                    }

                    // Stream ended without an explicit close frame
                    if !STOP.load(Ordering::Relaxed) {
                        call_kotlin(&vm, "onWsDisconnected", "Connection lost");
                    }
                }
                Err(e) => {
                    call_kotlin(&vm, "onWsError", &format!("Connect failed: {}", e));
                    tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                }
            }
        }

        call_kotlin(&vm, "onWsStopped", "Service stopped");
    });
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_stopWs(
    _env: JNIEnv,
    _class: JClass,
) {
    STOP.store(true, Ordering::Relaxed);
}
