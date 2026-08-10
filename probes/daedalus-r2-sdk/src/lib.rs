//! Public compile probe for the AWS SDK surface used by Daedalus's private R2
//! archive adapter. This intentionally contains no Daedalus business logic,
//! credentials, bucket names, object keys, or private source.

use std::{collections::HashMap, error::Error, path::PathBuf};

use aws_config::BehaviorVersion;
use aws_sdk_s3::{
    config::{Credentials, Region},
    operation::head_object::HeadObjectOutput,
    primitives::ByteStream,
    types::ChecksumMode,
    Client,
};

pub async fn build_r2_client(
    endpoint: &str,
    access_key_id: &str,
    secret_access_key: &str,
) -> Client {
    let credentials = Credentials::new(
        access_key_id,
        secret_access_key,
        None,
        None,
        "daedalus-r2-sdk-probe",
    );
    let shared_config = aws_config::defaults(BehaviorVersion::v2026_01_12())
        .endpoint_url(endpoint)
        .credentials_provider(credentials)
        .region(Region::new("auto"))
        .load()
        .await;
    let service_config = aws_sdk_s3::config::Builder::from(&shared_config)
        .force_path_style(true)
        .build();
    Client::from_conf(service_config)
}

pub async fn probe_conditional_write_and_error_surface(
    client: &Client,
    bucket: &str,
    object_key: &str,
    path: PathBuf,
    byte_count: u64,
    checksum_sha256: &str,
    media_type: Option<&str>,
    metadata: HashMap<String, String>,
) -> Result<(Option<u16>, Option<u16>), Box<dyn Error + Send + Sync>> {
    let head_status = match client
        .head_object()
        .bucket(bucket)
        .key(object_key)
        .checksum_mode(ChecksumMode::Enabled)
        .send()
        .await
    {
        Ok(output) => {
            let _ = decode_head(&output)?;
            None
        }
        Err(error) => {
            let _not_found = error
                .as_service_error()
                .is_some_and(|service| service.is_not_found());
            error
                .raw_response()
                .map(|response| response.status().as_u16())
        }
    };

    let body = ByteStream::from_path(path).await?;
    let mut request = client
        .put_object()
        .bucket(bucket)
        .key(object_key)
        .body(body)
        .content_length(i64::try_from(byte_count)?)
        .checksum_sha256(checksum_sha256)
        .if_none_match("*")
        .set_metadata(Some(metadata));
    if let Some(media_type) = media_type {
        request = request.content_type(media_type);
    }
    let put_status = match request.send().await {
        Ok(_) => None,
        Err(error) => error
            .raw_response()
            .map(|response| response.status().as_u16()),
    };

    Ok((head_status, put_status))
}

pub type DecodedHead = (u64, Option<String>, Option<String>, HashMap<String, String>);

pub fn decode_head(output: &HeadObjectOutput) -> Result<DecodedHead, Box<dyn Error + Send + Sync>> {
    let byte_count = output
        .content_length()
        .and_then(|value| u64::try_from(value).ok())
        .ok_or("missing content length")?;
    let media_type = output.content_type().map(str::to_ascii_lowercase);
    let checksum_sha256 = output.checksum_sha256().map(str::to_string);
    let metadata = output.metadata().cloned().unwrap_or_default();
    Ok((byte_count, media_type, checksum_sha256, metadata))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn head_output_accessors_match_adapter_assumptions() {
        let output = HeadObjectOutput::builder()
            .content_length(17)
            .content_type("model/gltf-binary")
            .checksum_sha256("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=")
            .metadata("daedalus-sha256", "a".repeat(64))
            .build();
        let (byte_count, media_type, checksum_sha256, metadata) =
            decode_head(&output).expect("decode head");
        assert_eq!(byte_count, 17);
        assert_eq!(media_type.as_deref(), Some("model/gltf-binary"));
        assert_eq!(
            checksum_sha256.as_deref(),
            Some("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=")
        );
        assert_eq!(
            metadata.get("daedalus-sha256").map(String::as_str),
            Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        );
    }
}
