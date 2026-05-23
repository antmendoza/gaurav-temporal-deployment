output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "region" {
  value = var.region
}

output "eks_admin_role_arn" {
  value = aws_iam_role.eks_admin.arn
}

output "db_host" {
  value = aws_db_instance.temporal.address
}

output "db_port" {
  value = aws_db_instance.temporal.port
}

output "db_username" {
  value = aws_db_instance.temporal.username
}

output "db_password" {
  value     = random_password.db.result
  sensitive = true
}
