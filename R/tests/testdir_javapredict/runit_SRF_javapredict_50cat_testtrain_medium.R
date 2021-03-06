#----------------------------------------------------------------------
# Purpose:  This test exercises the RF model downloaded as java code
#           for the dhisttest data set. It checks whether the generated
#           java correctly splits categorical predictors into non-
#           contiguous groups at each node.
#
# Notes:    Assumes unix environmen.
#           curl, javac, java must be installed.
#           java must be at least 1.6.
#----------------------------------------------------------------------

options(echo=FALSE)
TEST_ROOT_DIR <- ".."
setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source(paste(TEST_ROOT_DIR, "findNSourceUtils.R", sep="/"))


#----------------------------------------------------------------------
# Parameters for the test.
#----------------------------------------------------------------------

ntree <- 50
print(paste(    "ntrees"     , ntree))    

depth <- 5
print(paste(    "depth"     , depth))    

nodesize <- 10
print(paste( "nodesize", nodesize))

train <- locate("smalldata/histogram_test/50_cattest_train.csv")
print(paste(    "train"     , train))    

# test <- locate("smalldata/histogram_test/50_cattest_test.csv")
test <- locate("smalldata/histogram_test/50_cattest_test_limiteddigits.csv")
print(paste(    "test"     , test))    

x = c("x1", "x2")
print(    "x"     )    
print(x) 

y = "y"
print(paste(    "y" , y))


#----------------------------------------------------------------------
# Run the test
#----------------------------------------------------------------------
source('../Utils/shared_javapredict_SRF.R')
