rm -rf ~/.aws/sso/cache ~/.aws/cli/cache && aws configure sso --profile temporal-euw3


export AWS_PROFILE=temporal-euw3                                                                                
export AWS_REGION=eu-west-3                                                                                     
export TF_VAR_region=eu-west-3                                                                                  
export REGION=eu-west-3                                                                                         
export TF_STATE_BUCKET=temporal-dev-tfstate-238858505976-eu-west-3

cd ~/dev/temporal/temporal-server-play/gaurav-temporal-deployment/terraform/bootstrap                           
terraform workspace select eu-west-3                                                                            
terraform apply -var "bucket_name=$TF_STATE_BUCKET"




---

cd ../cluster                                                                                                   
terraform init -reconfigure \
-backend-config="bucket=$TF_STATE_BUCKET" \
-backend-config="region=eu-west-3"                                                                   
terraform apply



