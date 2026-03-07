use rusqlite::{params, Connection, Result};
use std::collections::HashMap;
use std::sync::Mutex;

#[derive(Debug)]
pub struct VectorStore {
    conn: Mutex<Connection>,
    vectors: Mutex<HashMap<String, Vec<f32>>>,
    ollama_url: String,
}

impl VectorStore {
    pub fn new(db_path: &str) -> Result<Self> {
        let conn = Connection::open(db_path)?;

        conn.execute(
            "CREATE TABLE IF NOT EXISTS vectors (
                article_id TEXT PRIMARY KEY,
                embedding BLOB NOT NULL,
                updated_at TEXT NOT NULL
            )",
            [],
        )?;

        let mut vectors = HashMap::new();

        {
            let mut stmt = conn.prepare("SELECT article_id, embedding FROM vectors")?;
            let rows = stmt.query_map([], |row| {
                let article_id: String = row.get(0)?;
                let embedding_bytes: Vec<u8> = row.get(1)?;
                Ok((article_id, embedding_bytes))
            })?;

            for row in rows {
                if let Ok((article_id, bytes)) = row {
                    let embedding: Vec<f32> = bytes
                        .chunks_exact(4)
                        .map(|chunk| f32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]))
                        .collect();
                    vectors.insert(article_id, embedding);
                }
            }
        }

        Ok(Self {
            conn: Mutex::new(conn),
            vectors: Mutex::new(vectors),
            ollama_url: "http://localhost:11434".to_string(),
        })
    }

    pub fn set_ollama_url(&mut self, url: &str) {
        self.ollama_url = url.to_string();
    }

    pub async fn generate_embedding(&self, text: &str) -> Result<Vec<f32>, String> {
        let truncated = text.chars().take(512).collect::<String>();
        
        let client = reqwest::Client::new();
        
        let response = client
            .post(format!("{}/api/embeddings", self.ollama_url))
            .json(&serde_json::json!({
                "model": "nomic-embed-text",
                "prompt": truncated
            }))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        #[derive(serde::Deserialize)]
        struct EmbeddingResponse {
            embedding: Vec<f32>,
        }

        let result: EmbeddingResponse = response.json().await.map_err(|e| e.to_string())?;
        
        Ok(result.embedding)
    }

    pub fn save_embedding(&self, article_id: &str, embedding: &[f32]) -> Result<()> {
        let conn = self.conn.lock().unwrap();

        let embedding_bytes: Vec<u8> = embedding
            .iter()
            .flat_map(|f| f.to_le_bytes())
            .collect();

        conn.execute(
            "INSERT OR REPLACE INTO vectors (article_id, embedding, updated_at) VALUES (?1, ?2, ?3)",
            params![
                article_id,
                embedding_bytes,
                chrono::Utc::now().to_rfc3339()
            ],
        )?;

        drop(conn);

        let mut vectors = self.vectors.lock().unwrap();
        vectors.insert(article_id.to_string(), embedding.to_vec());

        Ok(())
    }

    pub fn get_embedding(&self, article_id: &str) -> Result<Option<Vec<f32>>> {
        let vectors = self.vectors.lock().unwrap();
        Ok(vectors.get(article_id).cloned())
    }

    pub fn search(&self, query_embedding: &[f32], limit: usize) -> Vec<(String, f32)> {
        let vectors = self.vectors.lock().unwrap();

        let mut results: Vec<(String, f32)> = vectors
            .iter()
            .map(|(id, embedding)| {
                let similarity = cosine_similarity(query_embedding, embedding);
                (id.clone(), similarity)
            })
            .collect();

        results.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        results.truncate(limit);
        results
    }

    pub fn clear(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute("DELETE FROM vectors", [])?;

        let mut vectors = self.vectors.lock().unwrap();
        vectors.clear();

        Ok(())
    }

    pub fn count(&self) -> usize {
        let vectors = self.vectors.lock().unwrap();
        vectors.len()
    }
}

fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
    if a.len() != b.len() || a.is_empty() {
        return 0.0;
    }

    let dot_product: f32 = a.iter().zip(b.iter()).map(|(x, y)| x * y).sum();
    let magnitude_a: f32 = a.iter().map(|x| x * x).sum::<f32>().sqrt();
    let magnitude_b: f32 = b.iter().map(|x| x * x).sum::<f32>().sqrt();

    if magnitude_a == 0.0 || magnitude_b == 0.0 {
        return 0.0;
    }

    dot_product / (magnitude_a * magnitude_b)
}
