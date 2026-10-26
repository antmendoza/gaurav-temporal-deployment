# Execution role for the Lambda itself (logs only).
resource "aws_iam_role" "lambda_exec" {
  name = "${local.name}-exec"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_logs" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_caller_identity" "current" {}

locals {
  trusted_principal_arn = coalesce(
    var.trusted_principal_arn,
    "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root",
  )
}

# Role assumed to invoke this Lambda. ExternalId guards against the confused
# deputy problem — the caller must present the matching ID.
resource "aws_iam_role" "temporal_worker" {
  name = "${local.name}-temporal"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # External callers (e.g. Temporal Cloud) — gated by ExternalId.
      {
        Effect    = "Allow"
        Principal = { AWS = local.trusted_principal_arn }
        Action    = "sts:AssumeRole"
        Condition = {
          StringEquals = {
            "sts:ExternalId" = var.external_id
          }
        }
      },
    ]
  })
}

resource "aws_iam_role_policy" "invoke_lambda" {
  name = "invoke-lambda"
  role = aws_iam_role.temporal_worker.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "lambda:InvokeFunction"
      Resource = aws_lambda_function.worker.arn
    }]
  })
}
