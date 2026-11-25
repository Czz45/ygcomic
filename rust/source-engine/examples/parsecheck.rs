//! 离线解析体检：对订阅文件逐条跑 BookSource::from_json，统计成功率。不联网。
//! 用法：cargo run --example parsecheck
use source_engine::api::BookSource;

fn main() {
    let path = r"C:\Users\Administrator\.dsh\im\yiove_sub.txt";
    let raw = std::fs::read_to_string(path).expect("read yiove_sub.txt failed");
    let val: serde_json::Value = serde_json::from_str(&raw).expect("json parse failed");
    let arr = val.as_array().expect("not an array");
    let mut ok_cnt = 0usize;
    let mut fail_cnt = 0usize;
    for (i, item) in arr.iter().enumerate() {
        let one = item.to_string();
        match BookSource::from_json(&one) {
            Ok(_) => ok_cnt += 1,
            Err(e) => {
                fail_cnt += 1;
                if fail_cnt <= 20 {
                    let name = item.get("bookSourceName").and_then(|v| v.as_str()).unwrap_or("?");
                    println!("FAIL[{}] {} : {}", i, name, e);
                }
            }
        }
    }
    println!("TOTAL={} OK={} FAIL={}", arr.len(), ok_cnt, fail_cnt);
}
