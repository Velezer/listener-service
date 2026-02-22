use futures_util::StreamExt;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::runtime::Builder;
use tokio_tungstenite::connect_async;

static STOP: AtomicBool = AtomicBool::new(false);

const BUFFER_SIZE: usize = 128;
const RECONNECT_DELAY_SECONDS: u64 = 3;

/// Call a static void method on WsService with a single String argument.
fn call_kotlin(vm: &JavaVM, ws_service_class: &GlobalRef, method: &str, msg: &str) {
    let mut env = match vm.attach_current_thread() {
        Ok(env) => env,
        Err(err) => {
            eprintln!("Failed to attach JNI thread for {method}: {err}");
            return;
        }
    };

    let jmsg = match env.new_string(msg) {
        Ok(s) => s,
        Err(err) => {
            eprintln!("Failed to allocate Java string for {method}: {err}");
            return;
        }
    };

    let ws_service_class_ref: &JClass = ws_service_class.as_obj().into();

    if let Err(err) = env.call_static_method(
        ws_service_class_ref,
        method,
        "(Ljava/lang/String;)V",
        &[JValue::Object(&JObject::from(jmsg))],
    ) {
        eprintln!("Failed to call Kotlin method {method}: {err}");
    }
}

fn report_error(
    vm: &JavaVM,
    ws_service_class: &GlobalRef,
    context: &str,
    err: impl std::fmt::Display,
) {
    let message = format!("{context}: {err}");
    call_kotlin(vm, ws_service_class, "onWsError", &message);
    eprintln!("{message}");
}

fn decode_message(msg: tokio_tungstenite::tungstenite::Message) -> Result<Option<String>, String> {
    if msg.is_text() {
        let text = msg
            .into_text()
            .map_err(|err| format!("text frame decode error: {err}"))?;
        return Ok(Some(text.to_string()));
    }

    if msg.is_binary() {
        let data = msg.into_data();
        let len = data.len().min(BUFFER_SIZE);
        let truncated = &data[..len];
        let text = std::str::from_utf8(truncated)
            .map_err(|err| format!("binary frame is not valid UTF-8: {err}"))?;
        return Ok(Some(text.to_string()));
    }

    if msg.is_close() {
        return Ok(None);
    }

    Ok(Some(String::new()))
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_startWs(
    mut env: JNIEnv,
    class: JClass,
    url: JString,
) {
    let url: String = match env.get_string(&url) {
        Ok(url) => url.into(),
        Err(err) => {
            eprintln!("Failed to read URL string from Java: {err}");
            return;
        }
    };

    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(err) => {
            eprintln!("Failed to retrieve JavaVM: {err}");
            return;
        }
    };

    let ws_service_class = match env.new_global_ref(class) {
        Ok(class) => class,
        Err(err) => {
            eprintln!("Failed to create global WsService class reference: {err}");
            return;
        }
    };

    STOP.store(false, Ordering::Relaxed);
    call_kotlin(
        &vm,
        &ws_service_class,
        "onWsStarted",
        "WebSocket listener started",
    );

    let rt = match Builder::new_current_thread().enable_all().build() {
        Ok(rt) => rt,
        Err(err) => {
            report_error(&vm, &ws_service_class, "Failed to build tokio runtime", err);
            call_kotlin(&vm, &ws_service_class, "onWsStopped", "Service stopped");
            return;
        }
    };

    rt.block_on(async move {
        let mut attempt: u32 = 0;

        while !STOP.load(Ordering::Relaxed) {
            attempt += 1;
            call_kotlin(
                &vm,
                &ws_service_class,
                "onWsConnecting",
                &format!("Connecting (attempt {})...", attempt),
            );

            match connect_async(&url).await {
                Ok((mut ws_stream, _)) => {
                    attempt = 0;
                    call_kotlin(&vm, &ws_service_class, "onWsConnected", &url);

                    while let Some(msg) = ws_stream.next().await {
                        if STOP.load(Ordering::Relaxed) {
                            break;
                        }

                        match msg {
                            Ok(m) => match decode_message(m) {
                                Ok(Some(text)) => {
                                    if text.is_empty() {
                                        continue;
                                    }
                                    let truncated: String =
                                        text.chars().take(BUFFER_SIZE).collect();
                                    call_kotlin(&vm, &ws_service_class, "onWsMessage", &truncated);
                                }
                                Ok(None) => {
                                    call_kotlin(
                                        &vm,
                                        &ws_service_class,
                                        "onWsDisconnected",
                                        "Server closed connection",
                                    );
                                    break;
                                }
                                Err(err) => {
                                    report_error(
                                        &vm,
                                        &ws_service_class,
                                        "Message decode error",
                                        err,
                                    );
                                }
                            },
                            Err(err) => {
                                report_error(&vm, &ws_service_class, "Read error", err);
                                break;
                            }
                        }
                    }

                    if !STOP.load(Ordering::Relaxed) {
                        call_kotlin(
                            &vm,
                            &ws_service_class,
                            "onWsDisconnected",
                            "Connection lost",
                        );
                    }
                }
                Err(err) => {
                    report_error(&vm, &ws_service_class, "Connect failed", err);
                    tokio::time::sleep(std::time::Duration::from_secs(RECONNECT_DELAY_SECONDS))
                        .await;
                }
            }
        }

        call_kotlin(&vm, &ws_service_class, "onWsStopped", "Service stopped");
    });
}

#[no_mangle]
pub extern "system" fn Java_com_listener_WsService_stopWs(_env: JNIEnv, _class: JClass) {
    STOP.store(true, Ordering::Relaxed);
}

#[cfg(test)]
mod tests {
    use super::decode_message;
    use tokio_tungstenite::tungstenite::Message;

    #[test]
    fn decode_text_message() {
        let msg = Message::Text("hello".into());
        let decoded = decode_message(msg).expect("text should decode");
        assert_eq!(decoded, Some("hello".to_string()));
    }

    #[test]
    fn decode_invalid_utf8_binary_errors() {
        let msg = Message::Binary(vec![0xff, 0xfe].into());
        let err = decode_message(msg).expect_err("invalid utf-8 should fail");
        assert!(err.contains("not valid UTF-8"));
    }
}
