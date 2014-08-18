package hex.glm;

import hex.FrameTask;
import hex.glm.GLMParams.Family;
import hex.gram.Gram;

import java.util.ArrayList;
import java.util.Arrays;

import water.H2O.H2OCountedCompleter;
import water.*;
import water.util.Utils;

/**
 * Contains all GLM related tasks.
 *
 * @author tomasnykodym
 *
 */

public abstract class GLMTask<T extends GLMTask<T>> extends FrameTask<T> {
  final protected GLMParams _glm;
  public GLMTask(Key jobKey, DataInfo dinfo, GLMParams glm){this(jobKey,dinfo,glm,null);}
  public GLMTask(Key jobKey, DataInfo dinfo, GLMParams glm,H2OCountedCompleter cmp){super(jobKey,dinfo,cmp);_glm = glm;}

  //helper function to compute eta - i.e. beta * row
  protected final double computeEta(final int ncats, final int [] cats, final double [] nums, final double [] beta){
    double res = 0;
    for(int i = 0; i < ncats; ++i)res += beta[cats[i]];
    final int numStart = _dinfo.numStart();
    for (int i = 0; i < nums.length; ++i) res += nums[i] * beta[numStart + i];
    res += beta[beta.length-1]; // intercept
    return res;
  }
  /**
   * Helper task to compute precise mean of response and number of observations.
   * (We skip rows with NAs, so we can't use Vec's mean in general.
   *
   * @author tomasnykodym
   *
   */
  static class YMUTask extends FrameTask<YMUTask>{
    private long []  _nobs;
    protected double [] _ymu;
    public double [] _ymin;
    public double [] _ymax;
    final int _nfolds;
    public YMUTask(Key jobKey, DataInfo dinfo,int nfolds) {this(jobKey,dinfo, nfolds, null);}
    public YMUTask(Key jobKey, DataInfo dinfo,int nfolds,  H2OCountedCompleter cmp) {
      super(jobKey,dinfo,cmp);
      _nfolds = nfolds;
    }
    @Override public void chunkInit(){
      super.chunkInit();
      _ymu = new double[_nfolds+1];
      _nobs = new long[_nfolds+1];
      _ymax = new double[_nfolds+1];
      _ymin = new double[_nfolds+1];
      Arrays.fill(_ymax,Double.NEGATIVE_INFINITY);
      Arrays.fill(_ymax,Double.POSITIVE_INFINITY);
    }
    @Override protected void processRow(long gid, double[] nums, int ncats, int[] cats, double [] responses) {
      double response = responses[0];
      _ymu[0] += response;
      ++_nobs[0];
      if(response < _ymin[0])_ymin[0] = response;
      if(response > _ymax[0])_ymax[0] = response;
      for(int i = 1; i < _nfolds+1; ++i) {
        if(gid % _nfolds == (i-1))
          continue;
        _ymu[i] += response;
        ++_nobs[i];
        if(response < _ymin[0])_ymin[i] = response;
        if(response > _ymax[i])_ymax[i] = response;
      }
    }
    @Override public void reduce(YMUTask t){
      if(t._nobs[0] != 0){
        if(_nobs[0] == 0){
          _ymu = t._ymu;
          _nobs = t._nobs;
          _ymin = t._ymin;
          _ymax = t._ymax;
        } else {
          for(int i = 0; i < _nfolds+1; ++i) {
            if(_nobs[i] + t._nobs[i] == 0)continue;
            _ymu[i] = _ymu[i] * ((double) _nobs[i] / (_nobs[i] + t._nobs[i])) + t._ymu[i] * t._nobs[i] / (_nobs[i] + t._nobs[i]);
            _nobs[i] += t._nobs[i];
            if(t._ymax[i] > _ymax[i])
              _ymax[i] = t._ymax[i];
            if(t._ymin[i] < _ymin[i])
              _ymin[i] = t._ymin[i];
          }
        }
      }
    }
    @Override protected void chunkDone(long n){
      for(int i = 0; i < _ymu.length; ++i)
        if(_nobs[i] != 0)_ymu[i] /= _nobs[i];
    }
    public double ymu(){return ymu(-1);}
    public long nobs(){return nobs(-1);}
    public double ymu(int foldId){return _ymu[foldId+1];}
    public long nobs(int foldId){return _nobs[foldId+1];}
  }
  /**
   * Task to compute Lambda Max for the given dataset.
   * @author tomasnykodym
   */
  static class LMAXTask extends GLMIterationTask {
    private double[] _z;
    private final double _gPrimeMu;
    private final double _alpha;

    //public GLMIterationTask(Job job, DataInfo dinfo, GLMParams glm, boolean computeGram, boolean validate, boolean computeGradient, double [] beta, double ymu, double reg, H2OCountedCompleter cmp) {


    public LMAXTask(Key jobKey, DataInfo dinfo, GLMParams glm, double ymu, long nobs, double alpha, float [] thresholds, H2OCountedCompleter cmp) {
      super(jobKey, dinfo, glm, false, true, true, glm.nullModelBeta(dinfo,ymu), ymu, 1.0/nobs, thresholds, cmp);
      _gPrimeMu = glm.linkDeriv(ymu);
      _alpha = alpha;
    }
    @Override public void chunkInit(){
      super.chunkInit();
      _z = MemoryManager.malloc8d(_grad.length);
    }
    @Override public void processRow(long gid, double[] nums, int ncats, int[] cats, double [] responses) {
      double w = (responses[0] - _ymu) * _gPrimeMu;
      for( int i = 0; i < ncats; ++i ) _z[cats[i]] += w;
      final int numStart = _dinfo.numStart();
      for(int i = 0; i < nums.length; ++i)
        _z[i+numStart] += w*nums[i];
      super.processRow(gid, nums, ncats, cats, responses);
    }
    @Override public void reduce(GLMIterationTask git){
      Utils.add(_z, ((LMAXTask)git)._z);
      super.reduce(git);
    }
    public double lmax(){
      double res = Math.abs(_z[0]);
      for( int i = 1; i < _z.length; ++i )
        if(res < _z[i])res = _z[i];
        else if(res < -_z[i])res = -_z[i];
      return _glm.variance(_ymu) * res / (_nobs * Math.max(_alpha,1e-3));
    }
  }

  /**
   * One iteration of glm, computes weighted gram matrix and t(x)*y vector and t(y)*y scalar.
   *
   * @author tomasnykodym
   *
   */
  public static class GLMLineSearchTask extends GLMTask<GLMLineSearchTask> {
    double [][] _betas;
    double []   _objvals;
    double _caseVal = 0;
    final double _l1pen;
    final double _l2pen;
    final double _reg;
    public GLMLineSearchTask(Key jobKey, DataInfo dinfo, GLMParams glm, double [] oldBeta, double [] newBeta, double betaEps, long nobs, double alpha, double lambda, H2OCountedCompleter cmp){
      super(jobKey,dinfo,glm,cmp);
      _l2pen = 0.5*(1-alpha)*lambda;
      _l1pen = alpha*lambda;
      _reg = 1.0/nobs;
      ArrayList<double[]> betas = new ArrayList<double[]>();
      double diff = 1;
      while(diff > betaEps && betas.size() < 100){
        diff = 0;
        double [] b = MemoryManager.malloc8d(oldBeta.length);
        for(int i = 0; i < oldBeta.length; ++i) {
          b[i] = 0.5 * (oldBeta[i] + newBeta[i]);
          double d = b[i] - oldBeta[i];
          if(d > diff) diff = d;
          else if(d < -diff) diff = -d;
        }
        betas.add(b);
        newBeta = b;
      }
      _betas = new double[betas.size()][];
      betas.toArray(_betas);
    }

    @Override public void chunkInit(){
      _objvals = new double[_betas.length];
    }
    @Override public void chunkDone(long n){
      for(int i = 0; i < _objvals.length; ++i)
        _objvals[i] *= _reg;
    }
    @Override public void postGlobal(){
      for(int i = 0; i < _objvals.length; ++i)
        for(double d:_betas[i])
          _objvals[i] += d*d*_l2pen + (d>=0?d:-d)*_l1pen;
    }
    @Override public final void processRow(long gid, final double [] nums, final int ncats, final int [] cats, double [] responses){
      for(int i = 0; i < _objvals.length; ++i){
        final double [] beta = _betas[i];
        double y = responses[0];
        _objvals[i] += _glm.deviance(y,_glm.linkInv(computeEta(ncats, cats,nums,beta)));
      }
    }
    @Override
    public void reduce(GLMLineSearchTask git){Utils.add(_objvals,git._objvals);}
  }

//  public static final class LMIterationTask extends FrameTask<LMIterationTask>{
//    public final int n_folds;
//    public long _n;
//    Gram [] _gram;
//    double [][] _xy;
//    public LMIterationTask(Key jobKey, DataInfo dinfo,int nfolds, H2OCountedCompleter cmp){
//      super(jobKey, dinfo, cmp);
//      n_folds = Math.max(1,nfolds);
//    }
//    @Override public void chunkInit(){
//      _gram = new Gram[n_folds];
//      _xy = new double[n_folds][];
//      for(int i = 0; i < n_folds; ++i){
//        _gram[i] = new Gram(_dinfo.fullN(), _dinfo.largestCat(), _dinfo._nums, _dinfo._cats,true);
//        _xy[i] = MemoryManager.malloc8d(_dinfo.fullN()+1); // + 1 is for intercept
//      }
//    }
//    @Override public final void processRow(long gid, final double [] nums, final int ncats, final int [] cats, double [] responses){
//      final int fold = n_folds == 1?0:(int)_n % n_folds;
//      final double y = responses[0];
//      _gram[fold].addRow(nums, ncats, cats, 1);
//      for(int i = 0; i < ncats; ++i){
//        final int ii = cats[i];
//        _xy[fold][ii] += responses[0];
//      }
//      final int numStart = _dinfo.numStart();
//      for(int i = 0; i < nums.length; ++i){
//        _xy[fold][numStart+i] += y*nums[i];
//      }
//      ++_n;
//    }
//
//    @Override public void chunkDone(){
//
//    }
//
//    @Override public void reduce(LMIterationTask lmit){
//      for(int i = 0; i < n_folds; ++i){
//        _gram[i].add(lmit._gram[i]);
//        Utils.add(_xy[i],lmit._xy[i]);
//      }
//    }
//  }

  /**
   * One iteration of glm, computes weighted gram matrix and t(x)*y vector and t(y)*y scalar.
   *
   * @author tomasnykodym
   */
  public static class GLMIterationTask extends GLMTask<GLMIterationTask> {
    final double [] _beta;
    protected Gram      _gram;
    double [] _xy;
    protected double [] _grad;
    double    _yy;
    GLMValidation _val; // validation of previous model
    final double _ymu;
    protected final double _reg;
    long _nobs;
    final boolean _validate;
    final float [] _thresholds;
    float [][] _newThresholds;
    int [] _ti;
    final boolean _computeGradient;
    final boolean _computeGram;
    public static final int N_THRESHOLDS = 50;

    public GLMIterationTask(Key jobKey, DataInfo dinfo, GLMParams glm, boolean computeGram, boolean validate, boolean computeGradient, double [] beta, double ymu, double reg, float [] thresholds, H2OCountedCompleter cmp) {
      super(jobKey, dinfo,glm,cmp);
      assert beta == null || beta.length == dinfo.fullN()+1:"beta.length != dinfo.fullN(), beta = " + beta.length + " dinfo = " + dinfo.fullN();
      _beta = beta;
      _ymu = ymu;
      _reg = reg;
      _computeGram = computeGram;
      _validate = validate;
      assert thresholds != null;
      _thresholds = _validate?thresholds:null;

      _computeGradient = computeGradient;
      assert !_computeGradient || validate;
    }

    private void sampleThresholds(int yi){
      if(_ti[yi] > (_newThresholds.length >> 2)) {
        Arrays.sort(_newThresholds[yi]);
        for (int i = 0; i < _ti[yi]; i += 2) {
          _newThresholds[yi][i >> 1] = _newThresholds[yi][i];
        }
        _ti[yi] = _newThresholds.length >> 2;
        for(int i = _ti[yi]; i < _newThresholds[yi].length;++i)
          _newThresholds[yi][i] = Float.POSITIVE_INFINITY;
      }
    }
    @Override public void processRow(long gid, final double [] nums, final int ncats, final int [] cats, double [] responses){
      ++_nobs;
      final double y = responses[0];
      assert ((_glm.family != Family.gamma) || y > 0) : "illegal response column, y must be > 0  for family=Gamma.";
      assert ((_glm.family != Family.binomial) || (0 <= y && y <= 1)) : "illegal response column, y must be <0,1>  for family=Binomial. got " + y;
      final double w, eta, mu, var, z;
      final int numStart = _dinfo.numStart();
      double d = 1;
      if( _glm.family == Family.gaussian){
        w = 1;
        z = y;
        mu = (_validate || _computeGradient)?computeEta(ncats,cats,nums,_beta):0;
      } else {
        if( _beta == null ) {
          mu = _glm.mustart(y, _ymu);
          eta = _glm.link(mu);
        } else {
          eta = computeEta(ncats, cats,nums,_beta);
          mu = _glm.linkInv(eta);
        }
        var = Math.max(1e-5, _glm.variance(mu)); // avoid numerical problems with 0 variance
        d = _glm.linkDeriv(mu);
        z = eta + (y-mu)*d;
        w = 1.0/(var*d*d);
      }
      if(_validate) {
        _val.add(y, mu);
        if(_glm.family == Family.binomial) {
          int yi = (int) y;
          if (_ti[yi] == _newThresholds[yi].length)
            sampleThresholds(yi);
          _newThresholds[yi][_ti[yi]++] = (float) mu;
        }
      }

      assert w >= 0 : "invalid weight " + w;
      final double wz = w * z;
      _yy += wz * z;
      if(_computeGradient || _computeGram){
        final double grad = _computeGradient?w*d*(mu-y):0;
        for(int i = 0; i < ncats; ++i){
          final int ii = cats[i];
          if(_computeGradient)_grad[ii] += grad;
          _xy[ii] += wz;
        }
        for(int i = 0; i < nums.length; ++i){
          _xy[numStart+i] += wz*nums[i];
          if(_computeGradient)
            _grad[numStart+i] += grad*nums[i];
        }
        if(_computeGradient)_grad[numStart + _dinfo._nums] += grad;
        _xy[numStart + _dinfo._nums] += wz;
        if(_computeGram)_gram.addRow(nums, ncats, cats, w);
      }
    }
    @Override protected void chunkInit(){
      if(_computeGram)_gram = new Gram(_dinfo.fullN(), _dinfo.largestCat(), _dinfo._nums, _dinfo._cats,true);
      _xy = MemoryManager.malloc8d(_dinfo.fullN()+1); // + 1 is for intercept
      int rank = 0;
      if(_beta != null)for(double d:_beta)if(d != 0)++rank;
      if(_validate){
        _val = new GLMValidation(null,_ymu, _glm,rank, _thresholds);
        if(_glm.family == Family.binomial){
          _ti = new int[2];
          _newThresholds = new float[2][N_THRESHOLDS << 2];
        }
      }
      if(_computeGradient)
        _grad = MemoryManager.malloc8d(_dinfo.fullN()+1); // + 1 is for intercept
    }

    @Override protected void chunkDone(long n){
      if(_computeGram)_gram.mul(_reg);
      for(int i = 0; i < _xy.length; ++i)
        _xy[i] *= _reg;
      if(_grad != null)
        for(int i = 0; i < _grad.length; ++i)
          _grad[i] *= _reg;
      _yy *= _reg;
      if(_validate && _glm.family == Family.binomial) {
        _newThresholds[0] = Arrays.copyOf(_newThresholds[0],Math.min(_ti[0],_newThresholds[0].length >> 1));
        _newThresholds[1] = Arrays.copyOf(_newThresholds[1],Math.min(_ti[1],_newThresholds[1].length >> 1));
      }
    }
    @Override
    public void reduce(GLMIterationTask git){
      if(_jobKey == null || Job.isRunning(_jobKey)) {
        Utils.add(_xy, git._xy);
        if (_computeGram) _gram.add(git._gram);
        _yy += git._yy;
        _nobs += git._nobs;
        if (_validate) _val.add(git._val);
        if (_computeGradient) Utils.add(_grad, git._grad);
        if(_validate && _glm.family == Family.binomial) {
          _newThresholds[0] = Utils.join(_newThresholds[0], git._newThresholds[0]);
          _newThresholds[1] = Utils.join(_newThresholds[1], git._newThresholds[1]);
          if (_newThresholds[0].length >= 2 * N_THRESHOLDS) {
            for (int i = 0; i < 2 * N_THRESHOLDS; i += 2)
              _newThresholds[0][i >> 1] = _newThresholds[0][i];
          }
          if (_newThresholds[0].length > N_THRESHOLDS)
            _newThresholds[0] = Arrays.copyOf(_newThresholds[0], N_THRESHOLDS);
          if (_newThresholds[1].length >= 2 * N_THRESHOLDS) {
            for (int i = 0; i < 2 * N_THRESHOLDS; i += 2)
              _newThresholds[1][i >> 1] = _newThresholds[1][i];
          }
          if (_newThresholds[1].length > N_THRESHOLDS)
            _newThresholds[1] = Arrays.copyOf(_newThresholds[1], N_THRESHOLDS);
        }
        super.reduce(git);
      }
    }

    @Override public void postGlobal(){
      if(_val != null){
        _val.computeAIC();
        _val.computeAUC();
      }
    }
    public double [] gradient(double alpha, double lambda){
      final double [] res = _grad.clone();
      if(_beta != null)
        for(int i = 0; i < res.length-1; ++i) res[i] += (1-alpha)*lambda*_beta[i];
      return res;
    }
  }
}
