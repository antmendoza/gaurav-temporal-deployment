data "terraform_remote_state" "cluster" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = "cluster.tfstate"
    region = var.region
  }
}