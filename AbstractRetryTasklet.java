package xxx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

public abstract class AbstractRetryTasklet implements Tasklet {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRetryTasklet.class);

  private RetryPolicy retryPolicy;
  private RetryTemplate retryTemplate;

  /**
   * リトライ実行する処理.
   *
   * <p>Tasklet 本来の execute を継承しないよう、引数を逆にしている.
   *
   * @param chunkContext ChunkContext.
   * @param contribution StepContribution.
   * @return RepeatStatus.
   */
  public abstract RepeatStatus execute(ChunkContext chunkContext, StepContribution contribution)
      throws Exception;

  /**
   * Tasklet 本来の execute メソッド.
   *
   * @param contribution StepContribution.
   * @param chunkContext ChunkContext.
   * @return RepeatStatus.
   */
  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    // retryTemplate の初期化.
    initRetryTemplate();

    try {
      if (retryTemplate != null) {
        // 指定回数分繰り返す.
        return retryTemplate.execute(
          (RetryCallback<RepeatStatus, Throwable>)
            retryContext -> execute(chunkContext, contribution));
      } else {
        // 繰り返し処理を行わない場合.
        return execute(chunkContext, contribution);
      }
    } catch (Throwable e) {
      // 指定回数失敗のため終了.

      // ログ出力.
      LOGGER.error(e.getMessage());

      // 終了ステータスセット.
      contribution.setExitStatus(ExitStatus.FAILED);
      return RepeatStatus.FINISHED;
    }
  }

  /**
   * リトライ回数を指定する.
   *
   * @param retryCount リトライ回数.
   * @return インスタンス.
   */
  public AbstractRetryTasklet retry(Integer retryCount) {
    return retry(retryCount, new ArrayList<>());
  }

  /**
   * リトライ回数を指定する.
   *
   * @param retryCount リトライ回数.
   * @param allowExceptions リトライする例外クラス.
   * @return インスタンス.
   */
  public AbstractRetryTasklet retry(
      Integer retryCount, List<Class<? extends Throwable>> allowExceptions) {
    if (retryCount != null) {
      // 実行回数.
      final int attemptCount = retryCount < 0 ? 1 : retryCount + 1;

      // リトライを行う例外List.
      final Map<Class<? extends Throwable>, Boolean> retryableExceptions =
          new HashMap<>() {
            {
              if (allowExceptions.isEmpty()) {
                put(Exception.class, true);
              } else {
                for (Class<? extends Throwable> exception : allowExceptions) {
                  put(exception, true);
                }
              }
            }
          };

      // リトライポリシーの作成.
      retryPolicy = new SimpleRetryPolicy(attemptCount, retryableExceptions);
    }
    return this;
  }

  /** retryTemplate の初期化. */
  private void initRetryTemplate() {
    if (retryPolicy != null) {
      retryTemplate =
          new RetryTemplate() {
            {
              setBackOffPolicy(new FixedBackOffPolicy());
              setRetryPolicy(retryPolicy);
            }
          };
    }
  }
}
