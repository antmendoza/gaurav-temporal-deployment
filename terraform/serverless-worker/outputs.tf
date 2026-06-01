output "lambda_arn" {
  value = aws_lambda_function.worker.arn
}

output "temporal_worker_role_arn" {
  value = aws_iam_role.temporal_worker.arn
}
